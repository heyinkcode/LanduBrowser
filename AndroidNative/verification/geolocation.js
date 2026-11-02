globalThis.landuParseExpectedGeolocation = (mode, latitude, longitude, accuracy) => {
  if (!['system', 'custom', 'blocked'].includes(mode)) throw new Error('请选择定位模式');
  if (mode !== 'custom') return { mode };
  const values = [latitude, longitude, accuracy].map(value => {
    if (typeof value !== 'string' || value.length > 40
      || !/^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/.test(value.trim())
      || !Number.isFinite(Number(value))) throw new Error('请填写有效的定位坐标与精度');
    return Number(value);
  });
  if (Math.abs(values[0]) > 90 || Math.abs(values[1]) > 180 || values[2] < 1 || values[2] > 1000000)
    throw new Error('纬度、经度或精度超出范围');
  return { mode, latitude: values[0], longitude: values[1], accuracy: values[2] };
};

globalThis.landuReadGeolocation = async expected => {
  const result = { schema: 1, supported: !!navigator.geolocation, secureContext: globalThis.isSecureContext === true,
    permissionBefore: null, permissionAfter: null, current: null, watchPositions: [], watchError: null, watchCleared: false };
  if (!result.supported || !result.secureContext) return result;
  const permissionState = async () => {
    try { return (await navigator.permissions.query({ name: 'geolocation' })).state; }
    catch { return 'unavailable'; }
  };
  const position = value => ({ kind: 'position', timestamp: value.timestamp,
    latitude: value.coords.latitude, longitude: value.coords.longitude, accuracy: value.coords.accuracy,
    altitude: value.coords.altitude, altitudeAccuracy: value.coords.altitudeAccuracy,
    heading: value.coords.heading, speed: value.coords.speed });
  const error = value => ({ kind: 'error', code: value?.code ?? 0, message: String(value?.message ?? value) });
  result.permissionBefore = await permissionState();
  // A blocked-policy check must never invite a permission prompt to pass it.
  if (expected.mode === 'blocked' && result.permissionBefore !== 'denied') {
    result.current = { kind: 'skipped', reason: '定位权限并未被拒绝，未发起会弹出授权的定位请求' };
  } else {
    result.current = await new Promise(resolve => {
      let finished = false;
      const finish = value => { if (!finished) { finished = true; clearTimeout(timer); resolve(value); } };
      const timer = setTimeout(() => finish(error({ code: 3, message: '定位检查超时' })), 15000);
      try { navigator.geolocation.getCurrentPosition(p => finish(position(p)), e => finish(error(e)),
        { enableHighAccuracy: true, maximumAge: 0, timeout: 12000 }); }
      catch (e) { finish(error(e)); }
    });
  }
  if (expected.mode === 'custom' && result.current?.kind === 'position') {
    await new Promise(resolve => {
      let token;
      let finished = false;
      const clear = () => {
        if (token !== undefined) {
          try { navigator.geolocation.clearWatch(token); result.watchCleared = true; }
          catch (e) { result.watchError = error(e); }
        }
      };
      const finish = () => { if (!finished) { finished = true; clearTimeout(timer); clear(); resolve(); } };
      const timer = setTimeout(() => { result.watchError = error({ code: 3, message: '持续定位更新超时' }); finish(); }, 10000);
      try {
        token = navigator.geolocation.watchPosition(p => {
          if (finished) return;
          result.watchPositions.push(position(p));
          if (result.watchPositions.length >= 2) finish();
        }, e => { result.watchError = error(e); finish(); },
        { enableHighAccuracy: true, maximumAge: 0, timeout: 8000 });
        if (finished) clear();
      } catch (e) { result.watchError = error(e); finish(); }
    });
  }
  result.permissionAfter = await permissionState();
  result.checkedAt = Date.now();
  return result;
};

globalThis.landuGeolocationChecks = (evidence, expected) => {
  evidence ??= {};
  const checks = [{ property: 'secure-geolocation-api', passed: evidence.supported === true && evidence.secureContext === true }];
  if (expected.mode === 'blocked') {
    checks.push({ property: 'permission-denied-before-and-after', passed: evidence.permissionBefore === 'denied' && evidence.permissionAfter === 'denied' });
    checks.push({ property: 'position-permission-denied', passed: evidence.current?.kind === 'error' && evidence.current.code === 1 });
  } else {
    checks.push({ property: 'permission-granted', passed: evidence.permissionAfter === 'granted' });
    const valid = value => value?.kind === 'position' && Number.isFinite(value.latitude) && Math.abs(value.latitude) <= 90
      && Number.isFinite(value.longitude) && Math.abs(value.longitude) <= 180 && Number.isFinite(value.accuracy) && value.accuracy > 0
      && Number.isFinite(value.timestamp) && Number.isFinite(evidence.checkedAt) && Math.abs(evidence.checkedAt - value.timestamp) <= 30000;
    checks.push({ property: 'fresh-current-position', passed: valid(evidence.current) });
    if (expected.mode === 'custom') {
      const positions = [evidence.current, ...(evidence.watchPositions ?? [])];
      checks.push({ property: 'two-watch-updates-and-cleanup', passed: evidence.watchPositions?.length === 2
        && evidence.watchCleared === true && evidence.watchError === null
        && evidence.watchPositions[1]?.timestamp > evidence.watchPositions[0]?.timestamp });
      checks.push({ property: 'all-custom-coordinates-and-accuracy', passed: positions.length === 3 && positions.every(value => valid(value)
        && Math.abs(value.latitude - expected.latitude) <= 1e-7 && Math.abs(value.longitude - expected.longitude) <= 1e-7
        && Math.abs(value.accuracy - expected.accuracy) <= 1e-6) });
      checks.push({ property: 'no-unconfigured-altitude-motion-values', passed: positions.length === 3 && positions.every(value =>
        ['altitude', 'altitudeAccuracy', 'heading', 'speed'].every(key => Object.hasOwn(value ?? {}, key) && value[key] === null)) });
    }
  }
  return checks;
};
