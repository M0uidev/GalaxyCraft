'use strict';
// The game's own settings (Super Minecraft Galaxy... in the pause menu), as GalaxyOptions.java
// declares them. The launcher shows them per installation and writes the same
// config/galaxycraft.properties the game reads; keys it does not know are kept.
// test/gamesettings.test.js checks this list against GalaxyOptions.java.
const properties = require('./properties');

const SCHEMA = [
  {
    key: 'movement', label: 'Movement', type: 'choice', default: 'MARIO',
    help: 'Mario: SMG2 moves Mario (his jumps and spins). Minecraft: Minecraft\'s physics move you, as Steve. '
      + 'Mario at Minecraft\'s speeds: Ctrl sprints, Shift sneaks. F6 switches them while playing.',
    options: [
      { value: 'MARIO', label: 'Mario' },
      { value: 'MINECRAFT', label: 'Minecraft' },
      { value: 'MARIO_MC', label: 'Mario at Minecraft\'s speeds' },
    ],
  },
  {
    key: 'skin', label: 'Skin', type: 'text', default: '', maxLength: 16,
    help: 'A Minecraft account\'s name: its skin goes on your character (also /skin <name>). Empty: Steve.',
  },
  {
    key: 'entityRange', label: 'Entity Distance', type: 'range', min: 16, max: 64, step: 8, default: 64, unit: ' blocks',
    help: 'How far from Mario the game draws mobs, items and particles.',
  },
  {
    key: 'cameraDistance', label: 'Camera Distance', type: 'range', min: 2, max: 16, step: 1, default: 4, unit: ' blocks',
    help: 'Third person: how far behind you the camera sits, walking on a planet.',
  },
  {
    key: 'cameraDistanceGliding', label: 'Camera Distance Gliding', type: 'range', min: 2, max: 16, step: 1, default: 6,
    unit: ' blocks', help: 'Third person, with the elytra open near a planet.',
  },
  {
    key: 'cameraDistanceSpace', label: 'Camera Distance in Space', type: 'range', min: 2, max: 16, step: 1, default: 10,
    unit: ' blocks', help: 'Third person, out in space, past every planet\'s gravity.',
  },
  {
    key: 'blockDistance', label: 'Planet Block Distance', type: 'range', min: 32, max: 160, step: 16, default: 96,
    unit: ' blocks', help: 'How far around Mario a planet is its real blocks; past that, its far view. Farther costs more.',
  },
  {
    key: 'farViewDetail', label: 'Far View Detail', type: 'range', min: 1, max: 6, step: 1, default: 2, unit: '',
    help: 'How far the far view stays fine past the blocks. Higher: finer farther out, more memory.',
  },
  {
    key: 'particles', label: 'Game Particles', type: 'toggle', default: true,
    help: 'Minecraft\'s particles (explosions, broken blocks, hits) drawn in the game.',
  },
];

/** A value made valid as the game would (Setting.valid): snapped, clamped, or the default. */
function validate(setting, value) {
  switch (setting.type) {
    case 'choice':
      return setting.options.some((o) => o.value === value) ? value : setting.default;
    case 'text':
      return typeof value === 'string' ? value.slice(0, setting.maxLength) : setting.default;
    case 'toggle':
      return value === true || value === 'true' ? true : value === false || value === 'false' ? false : setting.default;
    case 'range': {
      const n = typeof value === 'number' ? value : parseInt(String(value).trim(), 10);
      if (!Number.isFinite(n)) return setting.default;
      const snapped = setting.min + Math.round((n - setting.min) / setting.step) * setting.step;
      return Math.min(setting.max, Math.max(setting.min, snapped));
    }
    default:
      return value;
  }
}

/** The settings in a properties file's text, every one valid (missing ones at their default). */
function read(text) {
  const stored = properties.parse(text);
  const values = {};
  for (const s of SCHEMA) values[s.key] = stored.has(s.key) ? validate(s, stored.get(s.key)) : s.default;
  return values;
}

/** The file's new text: the values given written over the old text, other keys kept. */
function write(oldText, values) {
  const stored = properties.parse(oldText);
  for (const s of SCHEMA) {
    if (!(s.key in values)) continue;
    stored.set(s.key, String(validate(s, values[s.key])));
  }
  return properties.stringify(stored, 'GalaxyCraft settings');
}

module.exports = { SCHEMA, validate, read, write };
