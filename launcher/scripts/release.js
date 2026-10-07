'use strict';
// Releases a new launcher: bumps the version, commits, tags v<version> and pushes. CI then builds
// Linux and Windows and publishes both in one GitHub Release, and installed launchers update.
//   npm run release -- patch | minor | major | 1.2.3   [--no-push] [--game=/path/to/your/SMG2]
// The game module is remade first (pack-game) when its sources changed since release/module.
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
const [bump = 'patch', ...flags] = process.argv.slice(2);
const push = !flags.includes('--no-push');

const pkgFile = path.join(__dirname, '..', 'package.json');
const lockFile = path.join(__dirname, '..', 'package-lock.json');
const pkg = JSON.parse(fs.readFileSync(pkgFile, 'utf8'));
const [maj, min, pat] = pkg.version.split('.').map(Number);
const next = /^\d+\.\d+\.\d+$/.test(bump) ? bump
  : bump === 'major' ? `${maj + 1}.0.0` : bump === 'minor' ? `${maj}.${min + 1}.0` : bump === 'patch' ? `${maj}.${min}.${pat + 1}` : null;
if (!next) {
  console.error('Usage: npm run release -- patch | minor | major | x.y.z [--no-push]');
  process.exit(1);
}
const tag = `v${next}`;
if (git('status', '--porcelain')) {
  console.error('There are uncommitted changes: commit them first.');
  process.exit(1);
}
if (git('tag', '--list', tag)) {
  console.error(`The tag ${tag} already exists.`);
  process.exit(1);
}

// The module (release/module) must have been made from the current sources: CI cannot build it.
const { moduleHash } = require('./module-hash');
const moduleJson = path.join(__dirname, '..', '..', 'release', 'module', 'module.json');
let made = null;
try { made = JSON.parse(fs.readFileSync(moduleJson, 'utf8')).sourceHash; } catch { /* never made */ }
if (made !== moduleHash()) {
  const game = flags.find((f) => f.startsWith('--game=')) ? flags.find((f) => f.startsWith('--game=')).slice(7) : process.env.GXC_GAME;
  if (!game) {
    console.error('The game module changed since release/module was made. Make it again first (needs the toolchain and your disc):\n'
      + '  npm run pack-game -- --game "/path/to/Super Mario Galaxy 2.rvz"   then commit release/module\n'
      + 'or pass --game=/path/to/your/disc (or set GXC_GAME) and the release does it.');
    process.exit(1);
  }
  execFileSync(process.execPath, [path.join(__dirname, 'pack-game.js'), '--game', game], { stdio: 'inherit' });
  git('add', path.join(__dirname, '..', '..', 'release', 'module'));
}

pkg.version = next;
fs.writeFileSync(pkgFile, JSON.stringify(pkg, null, 2) + '\n');
if (fs.existsSync(lockFile)) {
  const lock = JSON.parse(fs.readFileSync(lockFile, 'utf8'));
  lock.version = next;
  if (lock.packages && lock.packages['']) lock.packages[''].version = next;
  fs.writeFileSync(lockFile, JSON.stringify(lock, null, 2) + '\n');
}
git('add', pkgFile, lockFile);
git('commit', '-m', `Super Minecraft Galaxy ${tag}`);
git('tag', '-a', tag, '-m', `Super Minecraft Galaxy Launcher ${tag}`);
console.log(`Committed and tagged ${tag}.`);
if (push) {
  const branch = git('rev-parse', '--abbrev-ref', 'HEAD');
  execFileSync('git', ['push', '-u', 'origin', branch], { stdio: 'inherit' });
  execFileSync('git', ['push', 'origin', tag], { stdio: 'inherit' });
  console.log(`Pushed: CI builds Linux and Windows and publishes the release ${tag}.`);
} else {
  console.log(`Push when ready: git push && git push origin ${tag}`);
}
