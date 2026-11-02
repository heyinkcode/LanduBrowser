// Local diagnostics. Never records frames/audio or attaches a stream to output.
globalThis.landuReadMedia = async () => {
  if (!navigator.mediaDevices) throw Error('MediaDevices unavailable');
  const permission = async name => {
    try { return (await navigator.permissions.query({ name })).state; }
    catch (error) { return { error: error.name }; }
  };
  const devices = await navigator.mediaDevices.enumerateDevices();
  const rows = devices.map(device => {
    let capabilities;
    try { capabilities = typeof device.getCapabilities === 'function' ? device.getCapabilities() : null; }
    catch (error) { capabilities = { error: error.name }; }
    return { kind: device.kind, deviceId: device.deviceId, groupId: device.groupId, label: device.label, capabilities };
  });
  return { schema: 1, secureContext: isSecureContext,
    permissions: { camera: await permission('camera'), microphone: await permission('microphone') }, devices: rows };
};

globalThis.landuReadBlockedMedia = async () => {
  const before = await landuReadMedia();
  const requests = [];
  for (const kind of ['audio', 'video']) {
    const started = performance.now();
    let expired = false, timer;
    const request = navigator.mediaDevices.getUserMedia({ [kind]: true }).then(stream => {
      const tracks = stream.getTracks(); tracks.forEach(track => track.stop());
      return { granted: true, tracksStopped: tracks.every(track => track.readyState === 'ended'), late: expired };
    }, error => ({ granted: false, error: error.name }));
    const value = await Promise.race([request, new Promise(resolve => {
      timer = setTimeout(() => { expired = true; resolve({ timeout: true }); }, 4000);
    })]);
    clearTimeout(timer);
    requests.push({ kind, ...value, elapsedMs: performance.now() - started });
  }
  return { before, requests, after: await landuReadMedia(),
    scope: 'Blocked capture must reject without opening devices; a granted stream is stopped immediately' };
};

globalThis.landuCaptureMedia = async () => {
  const before = await landuReadMedia();
  let expired = false, timer;
  const request = navigator.mediaDevices.getUserMedia({ audio: true, video: true }).then(async stream => {
    const tracks = stream.getTracks();
    let details;
    try {
      if (expired) return { late: true };
      const running = tracks.map(track => ({ kind: track.kind, label: track.label, readyState: track.readyState,
        settings: track.getSettings(), capabilities: track.getCapabilities() }));
      const active = await landuReadMedia();
      details = { granted: true, before, active, tracks: running,
        scope: 'Streams are inspected without recording, pixel access or playback, then immediately stopped' };
      return details;
    } finally {
      tracks.forEach(track => track.stop());
      if (details) details.tracksStopped = tracks.every(track => track.readyState === 'ended');
    }
  }, error => ({ granted: false, before, error: error.name }));
  try {
    return await Promise.race([request, new Promise(resolve => {
      timer = setTimeout(() => { expired = true; resolve({ timeout: true, before }); }, 60000);
    })]);
  } finally { clearTimeout(timer); }
};

globalThis.landuSelectMedia = async () => {
  const before = await landuReadMedia();
  const selections = [];
  for (const kind of ['audioinput', 'videoinput']) {
    const devices = before.devices.filter(device => device.kind === kind && /^[a-f0-9]{64}$/.test(device.deviceId)).slice(0, 4);
    for (const device of devices) {
      const type = kind === 'audioinput' ? 'audio' : 'video';
      let expired = false, timer;
      const request = navigator.mediaDevices.getUserMedia({ [type]: { deviceId: { exact: device.deviceId } } }).then(stream => {
        const tracks = stream.getTracks();
        const value = { requestedKind: kind, requestedId: device.deviceId, late: expired,
          tracks: tracks.map(track => ({ kind: track.kind, label: track.label, readyState: track.readyState, settings: track.getSettings() })) };
        tracks.forEach(track => track.stop());
        value.tracksStopped = tracks.every(track => track.readyState === 'ended');
        return value;
      }, error => ({ requestedKind: kind, requestedId: device.deviceId, error: error.name }));
      try {
        selections.push(await Promise.race([request, new Promise(resolve => {
          timer = setTimeout(() => { expired = true; resolve({ requestedKind: kind, requestedId: device.deviceId, timeout: true }); }, 8000);
        })]));
      } finally { clearTimeout(timer); }
    }
  }
  return { before, selections, scope: 'At most four enumerated devices per input kind; no frame/audio recording or playback' };
};
