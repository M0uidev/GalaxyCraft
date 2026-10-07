'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const java = require('../src/core/java');
const { fakeFs } = require('./helpers');

test('versions from folder names and release files', () => {
  assert.equal(java.versionFromName('jdk-25.0.1+8'), 25);
  assert.equal(java.versionFromName('java-25-openjdk-amd64'), 25);
  assert.equal(java.versionFromName('jdk-17.0.2'), 17);
  assert.equal(java.versionFromName('zulu25.30.17-ca-jdk25.0.1'), 25);
  assert.equal(java.versionFromName('25.0.1-tem'), 25);
  assert.equal(java.versionFromName('whatever'), 0);
  assert.equal(java.versionFromRelease('IMPLEMENTOR="x"\nJAVA_VERSION="25.0.1"\n'), 25);
  assert.equal(java.versionFromRelease('JAVA_VERSION="1.8.0_402"'), 8);
});

test('Linux: ~/.local/opt/jdk-25* as tools/gxplay.sh', () => {
  const fs = fakeFs({ '/h/.local/opt/jdk-25.0.1+8/bin/java': '', '/usr/lib/jvm/java-21-openjdk/bin/java': '' });
  const j = java.findJava({ platform: 'linux', env: {}, home: '/h' }, fs);
  assert.deepEqual(j, { home: '/h/.local/opt/jdk-25.0.1+8', version: 25, source: 'found' });
});

test('JAVA_HOME of 25 wins; of another version only as a last resort', () => {
  const fs = fakeFs({
    '/j25/bin/java': '', '/j25/release': 'JAVA_VERSION="25"',
    '/j21/bin/java': '', '/j21/release': 'JAVA_VERSION="21.0.1"',
    '/usr/lib/jvm/temurin-25-jdk/bin/java': '',
  });
  assert.equal(java.findJava({ platform: 'linux', env: { JAVA_HOME: '/j25' }, home: '/h' }, fs).home, '/j25');
  assert.equal(java.findJava({ platform: 'linux', env: { JAVA_HOME: '/j21' }, home: '/h' }, fs).home, '/usr/lib/jvm/temurin-25-jdk');
  const fs2 = fakeFs({ '/j21/bin/java': '', '/j21/release': 'JAVA_VERSION="21.0.1"' });
  assert.deepEqual(java.findJava({ platform: 'linux', env: { JAVA_HOME: '/j21' }, home: '/h' }, fs2),
    { home: '/j21', version: 21, source: 'JAVA_HOME' });
});

test('Windows: Program Files vendors, java.exe', () => {
  const fs = fakeFs({ 'C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.1.8-hotspot\\bin\\java.exe': '' }, 'win32');
  const j = java.findJava({ platform: 'win32', env: { ProgramFiles: 'C:\\Program Files' }, home: 'C:\\Users\\Mo' }, fs);
  assert.equal(j.home, 'C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.1.8-hotspot');
});

test('an installation\'s own Java is used as is, or reported missing', () => {
  const fs = fakeFs({ '/my/jdk/bin/java': '' });
  assert.equal(java.findJava({ chosen: '/my/jdk', platform: 'linux', env: {}, home: '/h' }, fs).source, 'installation');
  assert.equal(java.findJava({ chosen: '/none', platform: 'linux', env: {}, home: '/h' }, fs), null);
});
