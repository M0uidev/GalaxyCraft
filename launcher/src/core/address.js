'use strict';
// A friend's world as the player types it: "203.0.113.5:25599", "25.4.7.9" (a Hamachi address),
// "friend.example.com", "[::1]:25565". Checked here because the text becomes an argument of the
// Minecraft process: nothing but a host and a port may get through.

const DEFAULT_PORT = 25565;

function validHost(h) {
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) return h.split('.').every((n) => Number(n) <= 255);
  if (h.length > 253 || !/^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$/.test(h)) return false;
  return !h.includes('..') && h.split('.').every((label) => label.length >= 1 && label.length <= 63);
}

/** { host, port, text } for a good address, or { error } with words for the player. */
function parseAddress(input) {
  const raw = String(input == null ? '' : input).trim();
  if (!raw) return { error: 'Type your friend\'s address, like 25.4.7.9:25565.' };
  let host;
  let portText = null;
  let v6 = false;
  const br = /^\[([0-9A-Fa-f:.]+)\](?::(\d+))?$/.exec(raw);
  if (br) {
    host = br[1];
    portText = br[2] || null;
    v6 = true;
  } else if (/^[0-9A-Fa-f]*:[0-9A-Fa-f:]*:[0-9A-Fa-f:]*$/.test(raw)) { // bare IPv6 (two or more colons): no port
    host = raw;
    v6 = true;
  } else {
    const parts = raw.split(':');
    if (parts.length > 2) return { error: 'That address does not look right.' };
    host = parts[0];
    portText = parts.length === 2 ? parts[1] : null;
  }
  if (v6 ? !/^[0-9A-Fa-f:.]+$/.test(host) || !host.includes(':') : !validHost(host)) return { error: 'That address does not look right.' };
  let port = DEFAULT_PORT;
  if (portText !== null) {
    if (!/^\d{1,5}$/.test(portText) || Number(portText) < 1 || Number(portText) > 65535) return { error: 'The port must be a number from 1 to 65535.' };
    port = Number(portText);
  }
  return { host, port, text: v6 ? `[${host}]:${port}` : `${host}:${port}` };
}

module.exports = { DEFAULT_PORT, parseAddress };
