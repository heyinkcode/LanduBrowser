const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const id = value => typeof value === 'string' && (/^[a-f0-9]{64}$/.test(value) || ['default', 'communications'].includes(value));
const kinds = ['audioinput', 'videoinput', 'audiooutput'];
const names = { audioinput: /^Microphone [a-f0-9]{6}$/, videoinput: /^Camera [a-f0-9]{6}$/, audiooutput: /^Speaker [a-f0-9]{6}$/ };
const identities = value => value?.devices?.map(device => ({ kind: device.kind, deviceId: device.deviceId }))
  .sort((a, b) => `${a.kind}:${a.deviceId}`.localeCompare(`${b.kind}:${b.deviceId}`));

export function compareMedia({ initial, blocked, restoredPermission, isolated, hidden, restored, other, selection }) {
  const checks = [];
  const add = (scope, property, passed) => checks.push({ scope, property, passed: passed === true });
  for (const [round, contexts] of [['initial', initial], ['permission-restored', restoredPermission]]) {
    for (const context of ['main', 'iframe']) {
      const value = contexts?.[context]?.values;
      add(`${round}/${context}`, 'secure context, permission not yet granted', value?.secureContext === true
        && value?.permissions?.camera === 'prompt' && value.permissions.microphone === 'prompt');
      add(`${round}/${context}`, 'input devices observed', kinds.every(kind => value?.devices?.some(d => d.kind === kind)));
      add(`${round}/${context}`, 'names and identifiers withheld before permission', value?.devices?.length > 0
        && value.devices.every(d => d.label === '' && d.deviceId === '' && d.groupId === ''
          && (d.capabilities === null || same(d.capabilities, {}))));
    }
  }
  for (const phase of ['before', 'after']) {
    add('blocked', `${phase}: camera/microphone permission denied`, blocked?.[phase]?.permissions?.camera === 'denied'
      && blocked?.[phase]?.permissions?.microphone === 'denied');
  }
  add('blocked', 'one actual request per input kind', blocked?.requests?.length === 2
    && ['audio', 'video'].every(kind => blocked.requests.filter(value => value.kind === kind).length === 1));
  for (const kind of ['audio', 'video']) {
    const request = blocked?.requests?.find(value => value.kind === kind);
    add('blocked', `${kind}: denied before capture`, request?.granted === false && request.error === 'NotAllowedError'
      && !request.timeout && !request.late);
  }
  for (const [scope, value, hide] of [['A-isolated', isolated, false], ['A-hidden', hidden, true],
    ['A-restored', restored, false], ['B-isolated', other, false]]) {
    add(scope, 'actual stream granted and stopped', value?.granted === true && value.tracksStopped === true
      && !value.timeout && !value.late && value.tracks?.length === 2);
    add(scope, 'both permissions granted', value?.active?.permissions?.camera === 'granted'
      && value?.active?.permissions?.microphone === 'granted');
    const devices = value?.active?.devices;
    add(scope, 'all device kinds observed', kinds.every(kind => devices?.some(device => device.kind === kind)));
    add(scope, 'public labels match policy', devices?.length > 0 && devices.every(device => hide
      ? device.label === '' : names[device.kind]?.test(device.label)));
    add(scope, 'enumerated IDs and authorised capabilities coherent', devices?.length > 0 && devices.every(device =>
      id(device.deviceId) && id(device.groupId) && (device.kind === 'audiooutput' ? device.capabilities === null
        : device.capabilities?.deviceId === device.deviceId && device.capabilities.groupId === device.groupId
          && (device.kind === 'audioinput' ? device.capabilities.sampleRate?.max > 0 : device.capabilities.width?.max > 0))));
    for (const kind of ['audio', 'video']) {
      const track = value?.tracks?.find(track => track.kind === kind);
      const matching = devices?.find(device => device.kind === `${kind}input` && device.deviceId === track?.settings?.deviceId);
      add(scope, `${kind}: live track uses enumerated identity`, track?.readyState === 'live' && !!matching
        && track.label === matching.label && track.settings.groupId === matching.groupId
        && track.capabilities?.deviceId === track.settings.deviceId && track.capabilities.groupId === track.settings.groupId);
    }
  }
  for (const [scope, values, hide] of [['A-isolated', selection, false], ['A-hidden', hidden?.selection, true],
    ['A-restored', restored?.selection, false], ['B-isolated', other?.selection, false]]) {
    add(scope, 'explicit selection covers both input kinds', ['audioinput', 'videoinput'].every(kind =>
      values?.selections?.some(value => value.requestedKind === kind)));
    for (const kind of ['audioinput', 'videoinput']) {
      const requests = values?.selections?.filter(value => value.requestedKind === kind);
      add(scope, `${kind}: selected IDs work without fallback`, requests?.length > 0 && requests.every(value => {
        const track = value.tracks?.[0];
        return /^[a-f0-9]{64}$/.test(value.requestedId) && !value.error && !value.timeout && !value.late
          && value.tracksStopped === true && value.tracks?.length === 1 && track.readyState === 'live'
          && track.kind + 'input' === kind && track.settings?.deviceId === value.requestedId
          && (hide ? track.label === '' : names[kind].test(track.label));
      }));
    }
  }
  add('A', 'device IDs stable across hidden mode and normal process restarts',
    Array.isArray(identities(isolated?.active)) && same(identities(isolated.active), identities(hidden?.active))
      && same(identities(isolated.active), identities(restored?.active)));
  for (const kind of ['audioinput', 'videoinput']) {
    const a = isolated?.active?.devices?.filter(d => d.kind === kind && /^[a-f0-9]{64}$/.test(d.deviceId));
    const b = other?.active?.devices?.filter(d => d.kind === kind && /^[a-f0-9]{64}$/.test(d.deviceId));
    add('A/B', `${kind}: nondefault IDs differ between environments`, a?.length > 0 && b?.length > 0
      && a.every(value => b.every(other => other.deviceId !== value.deviceId)));
  }
  return { checks, passed: checks.length > 0 && checks.every(check => check.passed),
    scope: 'Xiaomi Android 15; two own environments, main-page capture and explicit input selection; main/iframe prepermission enumeration',
    doesNotCertify: ['custom labels', 'real identity mode', 'iframe capture and embedding permission policies',
      'media processing/output quality', 'cross-origin scenarios', 'Android 16', 'all hardware capability spoofing'] };
}
