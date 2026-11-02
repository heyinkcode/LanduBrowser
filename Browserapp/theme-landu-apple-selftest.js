#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const root = __dirname;
const read = (name) => fs.readFileSync(path.join(root, name), 'utf8');
const renderer = read('renderer.js');
const main = read('main.js');
const index = read('index.html');
const i18n = read('i18n.js');
const css = read('landu-apple.css');
const failures = [];
const check = (condition, message) => {
  if (!condition) failures.push(message);
  else console.log(`ok  ${message}`);
};

check(/'landu-apple':\s*\{\s*nameKey:\s*'theme\.landu\.name',\s*colorScheme:\s*'light'/.test(renderer), 'theme is registered as a light-only theme');
check(main.includes("'landu-apple': { bg: '#f5f5f7', overlay: '#ffffff', symbol: '#1d1d1f' }"), 'native titlebar colors are registered');
check(index.includes('landu-apple.css') && index.includes('data-ui-theme-option="landu-apple"'), 'stylesheet and theme picker option are wired');
check(i18n.includes("'theme.landu.name': '岚渡简约'") && i18n.includes("'theme.landu.name': 'Landu Minimal'"), 'Chinese and English theme labels exist');
check(css.includes('html[data-ui-theme="landu-apple"]') && css.includes('--landu-blue: #007aff') && css.includes('--landu-canvas: #f5f5f7'), 'scoped Apple palette tokens are defined');
check(css.includes('#view-profiles .table-card') && css.includes('.sidebar') && css.includes('.dialog-head'), 'environment list and shared shell components are styled');
check(css.includes('.theme-option[data-ui-theme-option="landu-apple"] > i'), 'theme preview tile is styled');

if (failures.length) {
  console.error(`\nFAIL ${failures.length}`);
  failures.forEach((failure) => console.error(` - ${failure}`));
  process.exit(1);
}
console.log('\nPASS theme-landu-apple-selftest');
