'use strict';
// The real thing, for CI (Linux and Windows): downloads Minecraft, Fabric and Mojang's Java from
// their servers the way the launcher does, then starts Minecraft with Fabric (and the mods in
// $MODS_DIR, the game's mod built by CI) and waits until Fabric has loaded the mods. A test
// account name and token: nothing online is touched.
//   node scripts/mc-integration.js [workdir]      (Linux without a display: under xvfb-run)
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { ensureMinecraft } = require('../src/main/mcinstall');
const mc = require('../src/core/minecraft');

const props = Object.fromEntries(fs.readFileSync(path.join(__dirname, '..', '..', 'fabric', 'gradle.properties'), 'utf8')
  .split('\n').map((l) => l.split('=')).filter((kv) => kv.length === 2).map(([k, v]) => [k.trim(), v.trim()]));

async function main() {
  const work = path.resolve(process.argv[2] || fs.mkdtempSync(path.join(os.tmpdir(), 'gxl-mc-')));
  const files = path.join(work, 'minecraft-files');
  const dirs = ['libraries', 'versions', 'assets', 'runtime', 'natives'].reduce((d, k) => ({ ...d, [k]: path.join(files, k) }), {});
  const want = { version: props.minecraft_version, fabricLoader: props.loader_version };
  console.log(`Minecraft ${want.version}, Fabric ${want.fabricLoader}, into ${work}`);
  let last = 0;
  const ready = await ensureMinecraft(dirs, want, {
    log: (l) => console.log(l),
    onProgress: (p) => {
      if (Date.now() - last > 5000 && p.total) { last = Date.now(); console.log(`  ${p.done}/${p.total} files, ${(p.bytes / 1e6).toFixed(0)} MB`); }
    },
  });
  // A second pass must find everything there (what PLAY does each time).
  const again = Date.now();
  await ensureMinecraft(dirs, want, { log: (l) => console.log(`second pass: ${l}`) });
  console.log(`second pass took ${Date.now() - again} ms`);

  const game = path.join(work, 'game');
  fs.mkdirSync(path.join(game, 'mods'), { recursive: true });
  if (process.env.MODS_DIR) {
    for (const f of fs.readdirSync(process.env.MODS_DIR)) if (f.endsWith('.jar')) fs.copyFileSync(path.join(process.env.MODS_DIR, f), path.join(game, 'mods', f));
  }
  if (props.fabric_api_version && fs.readdirSync(path.join(game, 'mods')).length) {
    // The game's mod needs Fabric API: from Fabric's Maven, as the launcher does.
    const v = props.fabric_api_version;
    const url = `${mc.FABRIC_MAVEN}${mc.mavenPath(`net.fabricmc.fabric-api:fabric-api:${v}`)}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Fabric API ${v}: HTTP ${res.status}`);
    fs.writeFileSync(path.join(game, 'mods', `fabric-api-${v}.jar`), Buffer.from(await res.arrayBuffer()));
  }
  const mods = fs.readdirSync(path.join(game, 'mods'));
  console.log(`mods: ${mods.join(', ') || '(none)'}`);
  const { cmd, args } = mc.command({
    version: ready.version, java: ready.java, dirs: { ...ready.dirs, game },
    auth: { name: 'LauncherTest', uuid: '00000000000000000000000000000000', accessToken: 'test', userType: 'msa' },
    jvmArgs: ['-Xmx2G', '-Dgalaxycraft.hidden=true'],
  });
  console.log(`starting ${cmd}`);
  const child = spawn(cmd, args, { cwd: game, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const want2 = mods.length ? /Loading \d+ mods/ : /Loading \d+ mods|Fabric Loader/;
  let seen = '';
  const result = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve({ ok: false, why: 'timed out' }), 240000);
    const onData = (d) => {
      const text = d.toString();
      process.stdout.write(text);
      seen += text;
      if (want2.test(seen) && (!mods.some((m) => m.startsWith('galaxycraft')) || /galaxycraft/.test(seen.split(/Loading \d+ mods/)[1] || ''))) {
        clearTimeout(timer);
        // Let it run a few seconds more: a mod that breaks at start shows up here.
        setTimeout(() => resolve({ ok: !/Exception in thread "main"|Crash report saved|Mixin apply failed/.test(seen) }), 8000);
      }
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', (code) => { clearTimeout(timer); resolve({ ok: false, why: `Minecraft exited (${code})` }); });
  });
  child.kill('SIGKILL');
  if (!result.ok) {
    console.error(`\nmc-integration: FAILED (${result.why || 'errors in the log'})`);
    process.exit(1);
  }
  console.log('\nmc-integration: Minecraft with Fabric started from the launcher\'s own files');
}

main().catch((e) => { console.error(e); process.exit(1); });
