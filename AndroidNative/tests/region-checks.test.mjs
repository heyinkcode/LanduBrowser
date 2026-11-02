import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const scope = vm.createContext({});
vm.runInContext(fs.readFileSync(new URL('../verification/region-checks.js', import.meta.url), 'utf8'), scope);
const expected = { languages: ['en-US', 'en'], timezone: 'America/Los_Angeles' };
const values = () => ({ language: 'en-US', languages: ['en-US', 'en'], intlLocale: 'en-US', numberLocale: 'en-US',
  timezone: 'America/Los_Angeles', timezoneOffsetJanuary: 480, timezoneOffsetJuly: 420 });
const contexts = () => Object.fromEntries(['main', 'iframe', 'dedicatedWorker', 'serviceWorker'].map(name => [name, { values: values() }]));
const headers = () => ({ navigation: { values: { 'accept-language': 'en-US,en;q=0.9' } },
  subresource: { values: { 'accept-language': 'en-US' } } });

test('dates use explicit IANA rules for DST, fractional offsets and day boundaries', () => {
  for (const [zone, january, july] of [['America/Los_Angeles', 480, 420], ['Europe/London', 0, -60],
    ['Asia/Tokyo', -540, -540], ['Asia/Kathmandu', -345, -345], ['Pacific/Kiritimati', -840, -840], ['UTC', 0, 0]]) {
    assert.equal(scope.landuTimezoneOffset(zone, Date.UTC(2026, 0, 15, 12)), january, zone);
    assert.equal(scope.landuTimezoneOffset(zone, Date.UTC(2026, 6, 15, 12)), july, zone);
  }
});
test('coherent regional APIs and request headers pass with exact context coverage', () => {
  const checks = scope.landuRegionChecks(contexts(), expected, headers());
  assert.equal(checks.length, 30);
  assert(checks.every(check => check.passed));
});
test('changing only the Intl zone label cannot hide stale native Date offsets', () => {
  const current = contexts();
  current.main.values.timezoneOffsetJanuary = -480;
  current.main.values.timezoneOffsetJuly = -480;
  const checks = scope.landuRegionChecks(current, expected, headers());
  assert.equal(checks.find(check => check.context === 'main' && check.property === 'timezone').passed, true);
  assert.equal(checks.find(check => check.context === 'main' && check.property === 'timezoneOffsetJanuary').passed, false);
  assert.equal(checks.find(check => check.context === 'main' && check.property === 'timezoneOffsetJuly').passed, false);
});
test('workers, Intl and HTTP headers cannot retain a different language', () => {
  const current = contexts();
  const requests = headers();
  current.dedicatedWorker.values.language = 'zh-CN';
  current.iframe.values.numberLocale = 'zh-CN';
  requests.navigation.values['accept-language'] = 'zh-CN,zh;q=0.9';
  const failures = scope.landuRegionChecks(current, expected, requests).filter(check => !check.passed);
  assert(failures.some(check => check.context === 'dedicatedWorker' && check.property === 'language'));
  assert(failures.some(check => check.context === 'iframe' && check.property === 'numberLocale'));
  assert(failures.some(check => check.context === 'navigation' && check.property === 'accept-language'));
});
test('missing contexts, missing headers and missing data never count as passed', () => {
  const current = contexts();
  delete current.serviceWorker;
  const checks = scope.landuRegionChecks(current, expected, {});
  assert(checks.some(check => check.context === 'serviceWorker' && !check.passed));
  assert(checks.some(check => check.context === 'subresource' && !check.passed));
  assert(scope.landuRegionChecks({}, { languages: null, timezone: null }, {}).every(check => !check.passed));
});
test('blank expectations still check Intl versus Date rather than copying a bad main offset', () => {
  const current = contexts();
  current.main.values.timezoneOffsetJuly = 480;
  const checks = scope.landuRegionChecks(current, { languages: null, timezone: null }, headers());
  assert.equal(checks.find(check => check.context === 'main' && check.property === 'timezoneOffsetJuly').passed, false);
});
test('expected input canonicalizes languages and rejects invalid timezone or empty list items', () => {
  const region = scope.landuParseExpectedRegion('EN-us,en,en-US', 'US/Pacific');
  assert.equal(JSON.stringify(region.languages), JSON.stringify(['en-US', 'en']));
  assert.equal(region.timezone, 'America/Los_Angeles');
  assert.throws(() => scope.landuParseExpectedRegion('en-US,', 'UTC'));
  assert.throws(() => scope.landuParseExpectedRegion('en_US', 'UTC'));
  assert.throws(() => scope.landuParseExpectedRegion('en-US', 'No/Such_Zone'));
});
