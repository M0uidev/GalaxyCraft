'use strict';
// The Mario Model setting (Steve's body, or the game's original Mario) for Mario movement. The
// game loads Mario's archives when it starts, so the launcher decides it at PLAY: the original one
// is the same Riivolution patch without the Steve archives, with its own descriptor.
const fs = require('node:fs');
const path = require('node:path');
const gamepack = require('./gamepack');

const XML = 'galaxycraft-mario.xml';
const JSON_FILE = 'galaxycraft-mario.json';

/** Whether the mod's settings file (galaxycraft.properties) asks for the original Mario. */
function wantsOriginal(propertiesText) {
  return /^\s*marioModel\s*=\s*original\s*$/mi.test(propertiesText || '');
}

/** The patch's XML without the lines that put Steve's archives in place of Mario's. */
function originalXml(xml) {
  return xml.split('\n').filter((line) => !/<file\s+disc="\/ObjectData\//.test(line)).join('\n');
}

/**
 * The installation's layout for this PLAY: with the original Mario asked for, its descriptor is the
 * one that leaves Steve's archives out (written next to the others); else the layout as it is.
 */
function layFor({ lay, rom, propertiesFile, platform = process.platform, fsApi = fs }) {
  let text = '';
  try { text = fsApi.readFileSync(propertiesFile, 'utf8'); } catch { /* none yet: Steve's */ }
  if (!wantsOriginal(text)) return lay;
  const xml = fsApi.readFileSync(path.join(lay.module, 'galaxycraft.xml'), 'utf8');
  fsApi.writeFileSync(path.join(lay.module, XML), originalXml(xml));
  const descriptor = path.join(path.dirname(lay.descriptor), JSON_FILE);
  fsApi.writeFileSync(descriptor, JSON.stringify(gamepack.descriptor(rom, lay.module, platform, XML), null, 2));
  return { ...lay, descriptor };
}

module.exports = { wantsOriginal, originalXml, layFor };
