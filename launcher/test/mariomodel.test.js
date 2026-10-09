'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const mariomodel = require('../src/core/mariomodel');

const XML = [
  '<patch id="galaxycraft">',
  '\t\t<file disc="/ObjectData/Mario.arc" external="/ObjectData/Mario.arc" />',
  '\t\t<file disc="/ObjectData/MarioCap.arc" external="/ObjectData/MarioCap.arc" />',
  '\t\t<folder disc="/StageData/GalaxyCraftSpace" external="/StageData/GalaxyCraftSpace" create="true" />',
  '</patch>',
].join('\n');

test('only marioModel=original asks for the original Mario', () => {
  assert.equal(mariomodel.wantsOriginal('marioModel=ORIGINAL\n'), true);
  assert.equal(mariomodel.wantsOriginal('movement=MARIO\nmarioModel = original'), true);
  assert.equal(mariomodel.wantsOriginal('marioModel=STEVE\n'), false);
  assert.equal(mariomodel.wantsOriginal(''), false);
  assert.equal(mariomodel.wantsOriginal(undefined), false);
});

test('the original patch leaves out Steve\'s archives and keeps the rest', () => {
  const out = mariomodel.originalXml(XML);
  assert.ok(!out.includes('ObjectData'));
  assert.ok(out.includes('GalaxyCraftSpace'));
});

test('layFor: Steve\'s layout as it is, or a descriptor without his archives', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mariomodel-'));
  const module_ = path.join(dir, 'module');
  fs.mkdirSync(module_);
  fs.writeFileSync(path.join(module_, 'galaxycraft.xml'), XML);
  const lay = { module: module_, descriptor: path.join(dir, 'galaxycraft.json') };
  const props = path.join(dir, 'galaxycraft.properties');
  assert.equal(mariomodel.layFor({ lay, rom: '/rom.iso', propertiesFile: props }), lay); // no file yet
  fs.writeFileSync(props, 'marioModel=STEVE\n');
  assert.equal(mariomodel.layFor({ lay, rom: '/rom.iso', propertiesFile: props }), lay);
  fs.writeFileSync(props, 'marioModel=ORIGINAL\n');
  const chosen = mariomodel.layFor({ lay, rom: '/rom.iso', propertiesFile: props, platform: 'linux' });
  assert.equal(path.basename(chosen.descriptor), 'galaxycraft-mario.json');
  const desc = JSON.parse(fs.readFileSync(chosen.descriptor, 'utf8'));
  assert.equal(desc['base-file'], '/rom.iso');
  assert.equal(path.basename(desc.riivolution.patches[0].xml), 'galaxycraft-mario.xml');
  assert.ok(!fs.readFileSync(path.join(module_, 'galaxycraft-mario.xml'), 'utf8').includes('ObjectData'));
  fs.rmSync(dir, { recursive: true });
});
