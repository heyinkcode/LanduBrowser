globalThis.landuDntChecks = (contexts, navigation, mode) => {
  if (!['enabled', 'disabled'].includes(mode)) throw new Error('请选择本次预期的 Do Not Track 状态');
  const expected = mode === 'enabled' ? '1' : null;
  const checks = [];
  for (const name of ['main', 'iframe', 'dedicatedWorker', 'serviceWorker']) {
    const actual = contexts[name]?.values;
    if (name === 'main' || name === 'iframe') {
      checks.push({ context: name, property: 'navigator.doNotTrack', expected,
        actual: actual?.value ?? null,
        passed: actual?.supported === true && actual.value === expected });
    }
    const headers = actual?.headers;
    checks.push({ context: name, property: 'request-header:DNT', expected, actual: headers?.dnt ?? null,
      passed: !!headers && Object.hasOwn(headers, 'dnt') && headers.dnt === expected });
  }
  checks.push({ context: 'navigation', property: 'request-header:DNT', expected, actual: navigation?.dnt ?? null,
    passed: !!navigation && Object.hasOwn(navigation, 'dnt') && navigation.dnt === expected });
  return checks;
};
