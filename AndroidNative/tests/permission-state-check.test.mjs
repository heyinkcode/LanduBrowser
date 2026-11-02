import test from 'node:test';
import assert from 'node:assert/strict';
import { parseMediaPermissions, parseAppOp, assessMediaDenial } from '../scripts/check-device-media-denial.mjs';

const camera = 'android.permission.CAMERA', microphone = 'android.permission.RECORD_AUDIO';
const user = (id, granted) => `  User ${id}: installed=true\n    runtime permissions:\n      ${camera}: granted=${granted}, flags=[ USER_FIXED ]\n      ${microphone}: granted=${granted}, flags=[ USER_FIXED ]\n`;
const sample = (granted, uidMode = 'ignore', packageMode = 'allow') => ({
  permissions: { [camera]: { granted }, [microphone]: { granted } },
  appOps: { CAMERA: { uidMode, packageMode }, RECORD_AUDIO: { uidMode, packageMode } },
});

test('uses the current Android user rather than another profile', () => {
  const dump = user(10, true) + user(0, false);
  assert.equal(parseMediaPermissions(dump, 0)[camera].granted, false);
  assert.equal(parseMediaPermissions(dump, 10)[microphone].granted, true);
  assert.throws(() => parseMediaPermissions(dump, 11));
});
test('does not turn missing, duplicated or malformed data into denial', () => {
  assert.throws(() => parseMediaPermissions('No package found', 0));
  assert.throws(() => parseMediaPermissions(user(0, false).replace(microphone, 'other.permission'), 0));
  assert.throws(() => parseMediaPermissions(user(0, false) + `      ${camera}: granted=false, flags=[]\n`, 0));
  assert.throws(() => parseMediaPermissions(user(0, false).replace('granted=false', 'granted=unknown'), 0));
});
test('a late regrant fails even when first and final observations look denied', () => {
  assert.deepEqual(assessMediaDenial([sample(false), sample(true), sample(false)]).failedSamples, [1]);
  assert.equal(assessMediaDenial([]).passed, false);
  assert.equal(assessMediaDenial([{ permissions: {} }]).passed, false);
});
test('records UID and package AppOps independently and applies UID override', () => {
  const op = parseAppOp('Uid mode: CAMERA: ignore\nCAMERA: allow; time=+2h ago; duration=353ms', 'CAMERA');
  assert.equal(op.uidMode, 'ignore'); assert.equal(op.packageMode, 'allow');
  assert.equal(assessMediaDenial([sample(false)], true).passed, true);
  assert.equal(assessMediaDenial([sample(false, 'foreground')], true).passed, false);
  assert.equal(assessMediaDenial([sample(false, null, 'ignore')], true).passed, true);
  assert.equal(assessMediaDenial([sample(false, 'allow', 'ignore')], true).passed, false);
});
