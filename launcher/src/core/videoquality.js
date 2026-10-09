'use strict';
// The Picture Quality setting (VideoQuality.java) as Dolphin's graphics settings, passed at PLAY
// with -C (for this run only; they are not saved in Dolphin's GFX.ini). Dolphin names the graphics
// area "Graphics" there.

/** Dolphin's InternalResolution (1 = native), MSAA (1 = off), MaxAnisotropy (0 = 1x ... 4 = 16x). */
const LEVELS = {
  LOW: [1, 1, 0],
  MEDIUM: [2, 2, 2],
  HIGH: [3, 4, 3],
  ULTRA: [4, 4, 3],
};
const DEFAULT = 'HIGH';

/** The level the mod's settings file (galaxycraft.properties) asks for, High if it says none. */
function levelOf(propertiesText) {
  const m = /^\s*videoQuality\s*=\s*(\w+)\s*$/mi.exec(propertiesText || '');
  const name = m ? m[1].toUpperCase() : DEFAULT;
  return LEVELS[name] ? name : DEFAULT;
}

/** The -C arguments for Dolphin. */
function videoArgs(propertiesText) {
  const [res, msaa, aniso] = LEVELS[levelOf(propertiesText)];
  return [
    '-C', `Graphics.Settings.InternalResolution=${res}`,
    '-C', `Graphics.Settings.MSAA=${msaa}`,
    '-C', `Graphics.Enhancements.MaxAnisotropy=${aniso}`,
  ];
}

module.exports = { LEVELS, levelOf, videoArgs };
