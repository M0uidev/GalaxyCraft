'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { parseAddress } = require('../src/core/address');

test('addresses a player may type', () => {
  assert.deepStrictEqual(parseAddress('25.4.7.9:25599'), { host: '25.4.7.9', port: 25599, text: '25.4.7.9:25599' });
  assert.strictEqual(parseAddress(' 192.168.1.20 ').text, '192.168.1.20:25565');
  assert.strictEqual(parseAddress('friend.example.com:1234').text, 'friend.example.com:1234');
  assert.strictEqual(parseAddress('localhost:25599').text, 'localhost:25599');
  assert.strictEqual(parseAddress('[::1]:25599').text, '[::1]:25599');
  assert.strictEqual(parseAddress('fe80::1').text, '[fe80::1]:25565');
});

test('anything that could be an argument or is not an address is refused', () => {
  for (const bad of ['', '   ', '--username', '-x:25565', 'a b', '1.2.3.4:0', '1.2.3.4:70000', '1.2.3.4:abc', '999.1.1.1', 'a..b',
    'host:1:2', 'host;rm', '$(id)', '1.2.3.4 --quickPlaySingleplayer x', '.example.com', 'exa_mple.com', null, undefined]) {
    assert.ok(parseAddress(bad).error, `refused: ${bad}`);
  }
});
