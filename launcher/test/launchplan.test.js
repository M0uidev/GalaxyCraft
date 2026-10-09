'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { buildPlan, splitArgs, describe: describePlan } = require('../src/core/launchplan');
const { platformPaths } = require('../src/core/paths');
const store = require('../src/core/store');

test('arguments typed by the player', () => {
  assert.deepEqual(splitArgs(' --a  "b c" \'d e\' f\\"g "h\\"i" '), ['--a', 'b c', 'd e', 'f\\"g', 'h"i']);
  assert.deepEqual(splitArgs(''), []);
});

test('Linux: the same processes as tools/gxplay.sh', () => {
  const paths = platformPaths({ platform: 'linux', env: {}, home: '/h' });
  const plan = buildPlan({
    root: '/g', paths, inst: store.defaultInstallation(), javaHome: '/jdk', dolphinBin: '/g/dolphin/build/Binaries/dolphin-emu',
    env: { PATH: '/bin' },
  });
  const [dolphin, mc] = plan.processes;
  assert.equal(dolphin.cmd, '/g/dolphin/build/Binaries/dolphin-emu');
  assert.deepEqual(dolphin.args, [
    '-u', '/h/.local/share/galaxycraft/dolphin', '-b', '-e', '/g/syati/build/galaxycraft.json',
    '-C', 'Dolphin.Input.BackgroundInput=True', '-C', 'Dolphin.General.HotkeysRequireFocus=False',
    '-C', 'Dolphin.Core.RAMOverrideEnable=True', '-C', 'Dolphin.Core.MEM2Size=268435456',
    '-C', 'Dolphin.Core.CPUThread=True', '-C', 'Dolphin.Interface.ConfirmStop=False',
  ]);
  assert.equal(dolphin.env.GALAXYCRAFT, '1');
  assert.equal(dolphin.env.GALAXYCRAFT_BOOT, 'space');
  assert.equal(dolphin.env.PATH, '/bin');
  assert.equal(mc.cmd, '/jdk/bin/java');
  assert.equal(mc.cwd, '/g/fabric');
  assert.equal(mc.env.JAVA_HOME, '/jdk');
  assert.deepEqual(mc.args.slice(3), ['-jar', '/g/fabric/gradle/wrapper/gradle-wrapper.jar', 'runClient',
    '-PgalaxycraftHidden', '-PgalaxycraftGameDir=/h/.local/share/galaxycraft/minecraft', '--console=plain', '-q']);
  assert.equal(plan.stopMatch, 'galaxycraft.hidden=true');
  assert.equal(plan.seed.smg2Save.to, '/h/.local/share/galaxycraft/dolphin/Wii/title/00010000/53423445');
  assert.equal(plan.seed.smg2Save.candidates[0], '/h/.local/share/dolphin-emu/Wii/title/00010000/53423445');
  assert.equal(plan.seed.dolphinConfig.fromUser[0].candidates[0], '/h/.config/dolphin-emu/GFX.ini');
});

test('Windows: java.exe, backslashes, the installation\'s options', () => {
  const paths = platformPaths({ platform: 'win32', env: { APPDATA: 'C:\\A' }, home: 'C:\\Users\\Mo' });
  const inst = { ...store.defaultInstallation(), id: 'speed', dualCore: false, fullscreen: true,
    dolphinArgs: '-C "Dolphin.Display.RenderToMain=True"', gradleArgs: '--offline' };
  const plan = buildPlan({ root: 'C:\\g', paths, inst, javaHome: 'C:\\jdk', dolphinBin: 'C:\\g\\Dolphin.exe', env: {} });
  const [dolphin, mc] = plan.processes;
  assert.ok(dolphin.args.includes('Dolphin.Core.CPUThread=False'));
  assert.ok(dolphin.args.includes('Dolphin.Display.Fullscreen=True'));
  assert.deepEqual(dolphin.args.slice(-2), ['-C', 'Dolphin.Display.RenderToMain=True']);
  assert.equal(dolphin.args[1], 'C:\\A\\galaxycraft\\dolphin');
  assert.equal(mc.cmd, 'C:\\jdk\\bin\\java.exe');
  assert.ok(mc.args.includes('-PgalaxycraftGameDir=C:\\A\\galaxycraft\\installations\\speed'));
  assert.equal(mc.args.at(-1), '--offline');
  assert.match(describePlan(plan, 'win32')[0], /^\[Dolphin\] C:\\g\\Dolphin.exe -u /);
});

test('the chosen disc\'s descriptor, and no token in the log', () => {
  const paths = platformPaths({ platform: 'linux', env: {}, home: '/h' });
  const plan = buildPlan({ root: '/g', paths, inst: store.defaultInstallation(), javaHome: '/j', dolphinBin: '/d', descriptor: '/h/dev.json', env: {} });
  assert.equal(plan.processes[0].args[4], '/h/dev.json');
  plan.processes[1].args.push('--accessToken', 'secret-token');
  const lines = describePlan(plan, 'linux').join('\n');
  assert.doesNotMatch(lines, /secret-token/);
  assert.match(lines, /--accessToken \*{8}/);
});

test('Dolphin runs in batch mode (no main window) in the dev plan and the player plan, on both systems', () => {
  const gamepack = require('../src/core/gamepack');
  for (const platform of ['linux', 'win32']) {
    const paths = platformPaths({ platform, env: {}, home: platform === 'win32' ? 'C:\\Users\\Mo' : '/h' });
    const inst = { id: 'default', dualCore: true, dolphinArgs: '' };
    const dev = buildPlan({ root: '/g', paths, inst, javaHome: '/jdk', dolphinBin: '/d', env: {} }).processes[0].args;
    const player = gamepack.playerPlan({ paths, inst, lay: gamepack.layout(paths, '0.2.0'), dolphinExe: '/d', mcCommand: { cmd: 'java', args: [] }, env: {} }).processes[0].args;
    for (const args of [dev, player]) {
      assert.equal(args.filter((a) => a === '-b').length, 1, platform);
      assert.ok(args.indexOf('-b') < args.indexOf('-e'), 'before the game is named');
    }
  }
});
