'use strict';
// Java .properties files, as java.util.Properties reads and writes them: the game keeps its
// settings in config/galaxycraft.properties, and the launcher edits the same file.

function unescape(s) {
  return s.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, e) => {
    if (e[0] === 'u' && e.length === 5) return String.fromCharCode(parseInt(e.slice(1), 16));
    return { t: '\t', n: '\n', r: '\r', f: '\f' }[e] ?? e;
  });
}

/** Parses the text into an ordered Map of key -> value. */
function parse(text) {
  const out = new Map();
  const lines = String(text || '').split(/\r\n|\r|\n/);
  for (let i = 0; i < lines.length; i++) {
    let line = lines[i].replace(/^[ \t\f]+/, '');
    if (!line || line[0] === '#' || line[0] === '!') continue;
    // A line ending in an odd number of backslashes goes on with the next one.
    while (/(^|[^\\])(\\\\)*\\$/.test(line) && i + 1 < lines.length) {
      line = line.slice(0, -1) + lines[++i].replace(/^[ \t\f]+/, '');
    }
    let k = 0;
    while (k < line.length && !'=: \t\f'.includes(line[k])) k += line[k] === '\\' ? 2 : 1;
    const key = line.slice(0, k);
    let rest = line.slice(k).replace(/^[ \t\f]*/, '');
    if (rest[0] === '=' || rest[0] === ':') rest = rest.slice(1).replace(/^[ \t\f]*/, '');
    out.set(unescape(key), unescape(rest));
  }
  return out;
}

function escape(s, isKey) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    const code = s.charCodeAt(i);
    if (c === '\\') out += '\\\\';
    else if (c === '\t') out += '\\t';
    else if (c === '\n') out += '\\n';
    else if (c === '\r') out += '\\r';
    else if (c === '\f') out += '\\f';
    else if ('=:#!'.includes(c)) out += '\\' + c;
    else if (c === ' ' && (isKey || i === 0)) out += '\\ ';
    else if (code < 0x20 || code > 0x7e) out += '\\u' + code.toString(16).toUpperCase().padStart(4, '0');
    else out += c;
  }
  return out;
}

/** The text of a Map (or plain object) of key -> value, with a comment line on top. */
function stringify(entries, comment) {
  const map = entries instanceof Map ? entries : new Map(Object.entries(entries));
  const lines = [];
  if (comment) lines.push('#' + comment);
  lines.push('#' + new Date().toString());
  for (const [k, v] of map) lines.push(escape(String(k), true) + '=' + escape(String(v), false));
  return lines.join('\n') + '\n';
}

module.exports = { parse, stringify };
