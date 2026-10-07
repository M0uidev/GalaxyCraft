// The launcher's art, all drawn by code (no Mojang or Nintendo assets): block textures, the
// installation icons as little isometric blocks, and the animated space scenes behind PLAY.

// ---- textures --------------------------------------------------------------------------------

function rng(seed) {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13; s ^= s >>> 17; s ^= s << 5;
    return ((s >>> 0) % 100000) / 100000;
  };
}

const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];

/** A 16x16 texture: fn(x, y, r) -> '#rrggbb' or [r, g, b]. */
function texture(seed, fn) {
  const c = document.createElement('canvas');
  c.width = c.height = 16;
  const g = c.getContext('2d');
  const img = g.createImageData(16, 16);
  const r = rng(seed);
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const v = fn(x, y, r);
      const [R, G, B] = typeof v === 'string' ? hex(v) : v;
      const i = (y * 16 + x) * 4;
      img.data[i] = R; img.data[i + 1] = G; img.data[i + 2] = B; img.data[i + 3] = 255;
    }
  }
  g.putImageData(img, 0, 0);
  return c;
}

const pick = (r, list) => list[Math.floor(r() * list.length)];

const TEX = {};
function textures() {
  if (TEX.ready) return TEX;
  const grass = ['#5d9c33', '#6aad3a', '#4f8a2b', '#7bbd45', '#589530'];
  const dirt = ['#8a5a3b', '#7a4f33', '#966748', '#6d4630', '#a0714f'];
  const stone = ['#7d7d7d', '#8a8a8a', '#6f6f6f', '#959595', '#777777'];
  TEX.grassTop = texture(1, (x, y, r) => pick(r, grass));
  TEX.dirt = texture(2, (x, y, r) => pick(r, dirt));
  const fringe = Array.from({ length: 16 }, (_, i) => 3 + ((i * 7) % 3));
  TEX.grassSide = texture(3, (x, y, r) => (y < fringe[x] ? pick(r, grass) : pick(r, dirt)));
  TEX.stone = texture(4, (x, y, r) => pick(r, stone));
  TEX.diamond = texture(5, (x, y, r) => {
    const ore = [[3, 3], [4, 3], [3, 4], [10, 5], [11, 5], [11, 6], [5, 11], [6, 11], [6, 12], [12, 12], [13, 12]];
    return ore.some(([a, b]) => a === x && b === y) ? pick(r, ['#5decf5', '#3fd7e0', '#a8f7fb']) : pick(r, stone);
  });
  TEX.tntSide = texture(6, (x, y, r) => {
    if (y >= 6 && y <= 9) return x % 4 === 0 ? '#222222' : pick(r, ['#f2f2f2', '#e0e0e0']);
    return pick(r, ['#d63c2f', '#c33328', '#e04a3c']);
  });
  TEX.tntTop = texture(7, (x, y, r) => {
    const d = Math.hypot(x - 7.5, y - 7.5);
    return d < 2.5 ? '#3a3a3a' : d < 4 ? '#8b2a22' : pick(r, ['#d63c2f', '#c33328']);
  });
  TEX.ice = texture(8, (x, y, r) => ((x + y) % 7 === 0 ? '#e6f6ff' : pick(r, ['#9cc8f5', '#8fbdf0', '#a9d1f7'])));
  TEX.mushroom = texture(9, (x, y, r) => {
    const spots = [[3, 3], [4, 3], [3, 4], [4, 4], [11, 2], [12, 2], [11, 3], [12, 3], [8, 9], [9, 9], [8, 10], [9, 10], [2, 12], [3, 12], [13, 11], [13, 12]];
    return spots.some(([a, b]) => a === x && b === y) ? '#f4f0e6' : pick(r, ['#c8312a', '#b92a24', '#d43a31']);
  });
  TEX.planks = texture(10, (x, y, r) => (y % 4 === 3 ? '#6b4a26' : pick(r, ['#a7804b', '#9c7643', '#b08a52'])));
  TEX.craftTop = texture(11, (x, y, r) => {
    if (x === 0 || y === 0 || x === 15 || y === 15) return '#5b3d1f';
    if (x % 5 === 0 || y % 5 === 0) return '#6b4a26';
    return pick(r, ['#b08a52', '#a7804b', '#9c7643']);
  });
  TEX.craftSide = texture(12, (x, y, r) => {
    if (y < 3) return pick(r, ['#5b3d1f', '#6b4a26']);
    if (y > 5 && y < 13 && (x === 3 || x === 4) ) return '#8f8f8f';
    if (y > 5 && y < 8 && x > 1 && x < 7) return '#8f8f8f';
    if (y > 6 && y < 13 && x === 11) return '#6b4a26';
    if (y > 5 && y < 8 && x > 9 && x < 14) return '#3a2a14';
    return pick(r, ['#a7804b', '#9c7643', '#b08a52']);
  });
  TEX.ready = true;
  return TEX;
}

// ---- icons -----------------------------------------------------------------------------------

const BLOCKS = {
  grass: ['grassTop', 'grassSide', 'grassSide'],
  dirt: ['dirt', 'dirt', 'dirt'],
  stone: ['stone', 'stone', 'stone'],
  diamond: ['diamond', 'diamond', 'diamond'],
  tnt: ['tntTop', 'tntSide', 'tntSide'],
  ice: ['ice', 'ice', 'ice'],
  mushroom: ['mushroom', 'mushroom', 'mushroom'],
  crafting: ['craftTop', 'craftSide', 'planks'],
};

/** An isometric block (top, left, right faces), size x size. */
function drawBlock(g, size, [top, left, right]) {
  const t = textures();
  const s = size / 64;
  g.imageSmoothingEnabled = false;
  const face = (tex, a, b, c, d, e, f, shade) => {
    g.setTransform(a * s, b * s, c * s, d * s, e * s, f * s);
    g.drawImage(t[tex], 0, 0);
    if (shade) { g.fillStyle = `rgba(0,0,0,${shade})`; g.fillRect(0, 0, 16, 16); }
  };
  face(top, 2, 1, -2, 1, 32, 2, 0);
  face(left, 2, 1, 0, 2, 0, 18, 0.18);
  face(right, 2, -1, 0, 2, 32, 34, 0.36);
  g.setTransform(1, 0, 0, 1, 0, 0);
}

/** Smooth value noise on a lattice: the planets' land and seas. */
function noise(x, y, seed, scale = 3.5) {
  const v = (a, b) => {
    let h = (a * 374761393 + b * 668265263 + seed * 2147483647) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
  };
  const gx = x / scale;
  const gy = y / scale;
  const x0 = Math.floor(gx);
  const y0 = Math.floor(gy);
  let fx = gx - x0;
  let fy = gy - y0;
  fx = fx * fx * (3 - 2 * fx);
  fy = fy * fy * (3 - 2 * fy);
  const top = v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx;
  const bot = v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx;
  return top * (1 - fy) + bot * fy;
}

/** A little voxel planet as seen from space: grass, seas, beaches and trees, lit from the top left. */
function drawPlanet(g, cx, cy, radius, block, { light = [-0.55, -0.6], seed = 7, glow = true } = {}) {
  const r = rng(seed);
  const n = Math.ceil(radius / block);
  if (glow) {
    const a = g.createRadialGradient(cx, cy, radius * 0.9, cx, cy, radius * 1.35);
    a.addColorStop(0, 'rgba(120,200,255,0.35)');
    a.addColorStop(1, 'rgba(120,200,255,0)');
    g.fillStyle = a;
    g.beginPath(); g.arc(cx, cy, radius * 1.35, 0, Math.PI * 2); g.fill();
  }
  const scale = Math.max(2.5, n / 3); // features in proportion to the planet
  for (let j = -n; j <= n; j++) {
    for (let i = -n; i <= n; i++) {
      const x = (i + 0.5) * block;
      const y = (j + 0.5) * block;
      const d = Math.hypot(x, y);
      if (d > radius) { r(); continue; }
      const h = noise(i, j, seed, scale);
      const roll = r();
      let col;
      if (h < 0.32) col = roll < 0.5 ? [52, 98, 196] : [60, 110, 210];
      else if (h < 0.38) col = roll < 0.5 ? [219, 207, 142] : [206, 193, 128];
      else if (h > 0.84) col = roll < 0.5 ? [125, 125, 125] : [140, 140, 140];
      else if (roll < 0.12) col = roll < 0.06 ? [46, 96, 30] : [38, 82, 24];
      else col = pick(r, [[93, 156, 51], [106, 173, 58], [79, 138, 43], [123, 189, 69]]);
      // Light: the side of the sphere facing the light is brighter, the rim darker.
      const nz = Math.sqrt(Math.max(0, 1 - (d / radius) ** 2));
      const lit = 0.35 + 0.8 * Math.max(0, ((x / radius) * light[0] + (y / radius) * light[1]) * 0.6 + nz * 0.6);
      g.fillStyle = `rgb(${Math.min(255, col[0] * lit) | 0},${Math.min(255, col[1] * lit) | 0},${Math.min(255, col[2] * lit) | 0})`;
      g.fillRect(Math.round(cx + x - block / 2), Math.round(cy + y - block / 2), Math.ceil(block), Math.ceil(block));
    }
  }
}

// SMG2's Power Star, rendered from the game's model (scripts/powerstar.py). loadArt() waits for it.
const powerStar = new Image();
powerStar.src = 'powerstar.png';
export const loadArt = () => powerStar.decode().catch(() => {});

/** The Power Star, R pixels from its center to a tip. */
function drawStar(g, cx, cy, R) {
  if (!powerStar.naturalWidth) return;
  const size = R * 2.2; // the picture leaves a margin around the star
  g.imageSmoothingQuality = 'high';
  g.drawImage(powerStar, cx - size / 2, cy - size / 2, size, size);
}

const iconCache = new Map();
/** The icon of an installation as a data URL. */
export function iconUrl(name, size = 64) {
  const key = `${name}@${size}`;
  if (iconCache.has(key)) return iconCache.get(key);
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d');
  if (BLOCKS[name]) drawBlock(g, size, BLOCKS[name]);
  else if (name === 'star') drawStar(g, size / 2, size / 2 + size * 0.04, size * 0.44);
  else drawPlanet(g, size / 2, size / 2, size * 0.4, size / 16, { glow: false, seed: 3 });
  const url = c.toDataURL();
  iconCache.set(key, url);
  return url;
}

// ---- scenes ----------------------------------------------------------------------------------

export const SCENES = {
  planet: { label: 'Home Planet', sky: ['#05060f', '#0d1430', '#1b2350'] },
  nebula: { label: 'Nebula', sky: ['#07030f', '#1a0b2e', '#2c1250'] },
  night: { label: 'Night Sky', sky: ['#02040a', '#061026', '#0c1f45'] },
  sunrise: { label: 'Sunrise', sky: ['#1b1036', '#7a2f5a', '#f08a4b'] },
};

/** Draws a scene into a canvas. t: seconds (0 for a still frame). */
/** A planet drawn once into a sprite of its size, then copied: frames stay cheap. */
function planet(cache, g, cx, cy, radius, block, opts = {}) {
  const key = `${Math.round(radius)}:${block}:${opts.seed}:${opts.light}`;
  let sprite = cache.get(key);
  if (!sprite) {
    const half = Math.ceil(radius * 1.4);
    sprite = document.createElement('canvas');
    sprite.width = sprite.height = half * 2;
    drawPlanet(sprite.getContext('2d'), half, half, radius, block, opts);
    cache.set(key, sprite);
    if (cache.size > 24) cache.delete(cache.keys().next().value);
  }
  g.drawImage(sprite, Math.round(cx - sprite.width / 2), Math.round(cy - sprite.height / 2));
}

function paint(g, w, h, name, t, stars, cache) {
  const scene = SCENES[name] || SCENES.planet;
  const sky = g.createLinearGradient(0, 0, 0, h);
  sky.addColorStop(0, scene.sky[0]);
  sky.addColorStop(0.55, scene.sky[1]);
  sky.addColorStop(1, scene.sky[2]);
  g.fillStyle = sky;
  g.fillRect(0, 0, w, h);

  if (name === 'nebula') {
    for (const [x, y, rad, col] of [[0.25, 0.35, 0.45, '140,60,220'], [0.7, 0.25, 0.4, '40,120,230'], [0.55, 0.7, 0.5, '220,60,160']]) {
      const k = g.createRadialGradient(x * w, y * h, 0, x * w, y * h, rad * Math.max(w, h));
      k.addColorStop(0, `rgba(${col},0.38)`);
      k.addColorStop(1, `rgba(${col},0)`);
      g.fillStyle = k;
      g.fillRect(0, 0, w, h);
    }
  }
  if (name === 'sunrise') {
    const sun = g.createRadialGradient(w * 0.3, h * 1.05, 0, w * 0.3, h * 1.05, h * 0.9);
    sun.addColorStop(0, 'rgba(255,214,120,0.9)');
    sun.addColorStop(1, 'rgba(255,140,80,0)');
    g.fillStyle = sun;
    g.fillRect(0, 0, w, h);
  }

  // Stars twinkle.
  for (const s of stars) {
    const tw = 0.55 + 0.45 * Math.sin(t * s.speed + s.phase);
    g.fillStyle = `rgba(255,255,255,${(s.a * tw).toFixed(3)})`;
    const size = s.size;
    g.fillRect(Math.round(s.x * w), Math.round(s.y * h * (name === 'sunrise' ? 0.7 : 1)), size, size);
    if (size > 2 && tw > 0.85) {
      g.fillRect(Math.round(s.x * w) - size, Math.round(s.y * h), size * 3, 1);
      g.fillRect(Math.round(s.x * w) + size / 2, Math.round(s.y * h) - size, 1, size * 3);
    }
  }

  const unit = Math.max(6, Math.round(Math.min(w, h) / 70));
  if (name === 'planet') {
    planet(cache, g, w * 0.74, h * 0.92 + Math.sin(t * 0.2) * 4, h * 0.62, unit, { seed: 11 });
    planet(cache, g, w * 0.56, h * 0.16 + Math.sin(t * 0.3 + 1) * 6, h * 0.06, unit * 0.6, { seed: 5 });
    drawStar(g, w * 0.86, h * 0.17 + Math.sin(t * 1.3) * 5, Math.max(16, h * 0.07));
  } else if (name === 'night') {
    g.fillStyle = '#f2efd8';
    g.beginPath(); g.arc(w * 0.8, h * 0.2, h * 0.08, 0, Math.PI * 2); g.fill();
    g.fillStyle = SCENES.night.sky[0];
    g.beginPath(); g.arc(w * 0.8 + h * 0.035, h * 0.2 - h * 0.02, h * 0.075, 0, Math.PI * 2); g.fill();
    planet(cache, g, w * 0.2, h * 1.25, h * 0.6, unit, { seed: 21 });
    planet(cache, g, w * 0.55, h * 0.45 + Math.sin(t * 0.25) * 5, h * 0.05, unit * 0.5, { seed: 2 });
  } else if (name === 'nebula') {
    planet(cache, g, w * 0.3, h * 0.6 + Math.sin(t * 0.25) * 6, h * 0.16, unit * 0.8, { seed: 9 });
    planet(cache, g, w * 0.78, h * 0.3 + Math.sin(t * 0.2 + 2) * 6, h * 0.09, unit * 0.6, { seed: 13 });
  } else if (name === 'sunrise') {
    planet(cache, g, w * 0.68, h * 1.5, h * 0.85, unit, { seed: 17, light: [-0.7, -0.3] });
  }

  // A shooting star now and then.
  const cycle = t % 9;
  if (t > 0 && cycle < 1.2) {
    const k = cycle / 1.2;
    const x = w * (0.15 + 0.6 * k);
    const y = h * (0.08 + 0.25 * k);
    const trail = g.createLinearGradient(x, y, x - 120, y - 50);
    trail.addColorStop(0, `rgba(255,255,255,${0.9 * (1 - k)})`);
    trail.addColorStop(1, 'rgba(255,255,255,0)');
    g.strokeStyle = trail;
    g.lineWidth = 2;
    g.beginPath(); g.moveTo(x, y); g.lineTo(x - 120, y - 50); g.stroke();
  }
}

/**
 * Keeps a canvas drawn with a scene, animated (or still). Returns { set(name, animate), stop() }.
 * The planet layers are drawn once per size and cached, so a frame is only stars and blits.
 */
export function sceneOn(canvas) {
  const g = canvas.getContext('2d');
  const r = rng(99);
  const stars = Array.from({ length: 260 }, () => ({
    x: r(), y: r(), a: 0.3 + r() * 0.7, size: r() < 0.08 ? 3 : r() < 0.4 ? 2 : 1, speed: 0.5 + r() * 2.5, phase: r() * 6.28,
  }));
  const cache = new Map();
  let name = 'planet';
  let animate = true;
  let raf = 0;
  let last = 0;
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)');

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.max(1, Math.round(canvas.clientWidth * dpr));
    const h = Math.max(1, Math.round(canvas.clientHeight * dpr));
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  }
  function frame(now) {
    raf = 0;
    if (!canvas.isConnected) return;
    resize();
    const moving = animate && !reduce.matches;
    // ~30 frames a second is plenty for twinkling, and kind to laptops.
    if (moving && now - last < 33) { raf = requestAnimationFrame(frame); return; }
    last = now;
    paint(g, canvas.width, canvas.height, name, moving ? now / 1000 : 0, stars, cache);
    if (moving && !document.hidden) raf = requestAnimationFrame(frame);
  }
  const kick = () => { if (!raf) raf = requestAnimationFrame(frame); };
  new ResizeObserver(kick).observe(canvas);
  document.addEventListener('visibilitychange', kick);
  return {
    set(n, a) { name = n; animate = a; kick(); },
    stop() { cancelAnimationFrame(raf); raf = 0; },
  };
}

/** A small still preview of a scene, as a data URL (Settings' background tiles). */
export function scenePreview(name, w = 240, h = 135) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const r = rng(99);
  const stars = Array.from({ length: 90 }, () => ({ x: r(), y: r(), a: 0.4 + r() * 0.6, size: r() < 0.2 ? 2 : 1, speed: 1, phase: 1.57 }));
  paint(c.getContext('2d'), w, h, name, 0, stars, new Map());
  return c.toDataURL();
}
