globalThis.landuParseExpectedRegion = (languages, timezone) => {
  const tags = languages.trim() ? languages.split(',').map(tag => tag.trim()) : null;
  if (tags && (tags.length > 8 || tags.some(tag => !tag))) throw new Error('预期语言最多 8 项，用英文逗号分隔');
  const normalized = tags ? Intl.getCanonicalLocales(tags) : null;
  const zone = timezone.trim();
  return { languages: normalized, timezone: zone ? Intl.DateTimeFormat('en', { timeZone: zone }).resolvedOptions().timeZone : null };
};

globalThis.landuTimezoneOffset = (zone, timestamp) => {
  const formatter = Intl.DateTimeFormat('en-US-u-ca-gregory-nu-latn', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23', calendar: 'gregory', numberingSystem: 'latn',
  });
  const parts = Object.fromEntries(formatter.formatToParts(new Date(timestamp)).map(part => [part.type, part.value]));
  const represented = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour), Number(parts.minute));
  return (timestamp - represented) / 60_000;
};

globalThis.landuRegionChecks = (contexts, expected, requestHeaders) => {
  const localeBase = value => {
    try { return typeof value === 'string' ? new Intl.Locale(value).baseName.toLowerCase() : null; }
    catch { return null; }
  };
  const language = value => {
    try { return typeof value === 'string' ? Intl.getCanonicalLocales(value)[0]?.toLowerCase() ?? null : null; }
    catch { return null; }
  };
  const zone = value => {
    try { return typeof value === 'string' ? Intl.DateTimeFormat('en', { timeZone: value }).resolvedOptions().timeZone : null; }
    catch { return null; }
  };
  const main = contexts.main?.values;
  const languages = expected.languages ?? main?.languages;
  const validLanguages = Array.isArray(languages) && languages.length > 0 && languages.every(value => language(value) !== null);
  const primary = validLanguages ? language(languages[0]) : null;
  const selectedZone = zone(expected.timezone ?? main?.timezone);
  const offsets = selectedZone ? {
    timezoneOffsetJanuary: landuTimezoneOffset(selectedZone, Date.UTC(2026, 0, 15, 12)),
    timezoneOffsetJuly: landuTimezoneOffset(selectedZone, Date.UTC(2026, 6, 15, 12)),
  } : {};
  const checks = [];
  for (const name of ['main', 'iframe', 'dedicatedWorker', 'serviceWorker']) {
    const actual = contexts[name]?.values;
    checks.push({ context: name, property: 'language', actual: actual?.language ?? null, expected: primary,
      passed: primary !== null && language(actual?.language) === primary });
    checks.push({ context: name, property: 'languages', actual: actual?.languages ?? null, expected: languages ?? null,
      passed: validLanguages && Array.isArray(actual?.languages) && actual.languages.length === languages.length
        && actual.languages.every((value, i) => language(value) === language(languages[i])) });
    for (const key of ['intlLocale', 'numberLocale']) {
      checks.push({ context: name, property: key, actual: actual?.[key] ?? null, expected: primary,
        passed: primary !== null && localeBase(actual?.[key]) === localeBase(primary) });
    }
    checks.push({ context: name, property: 'timezone', actual: actual?.timezone ?? null, expected: selectedZone,
      passed: selectedZone !== null && zone(actual?.timezone) === selectedZone });
    for (const key of ['timezoneOffsetJanuary', 'timezoneOffsetJuly']) {
      checks.push({ context: name, property: key, actual: actual?.[key] ?? null, expected: offsets[key] ?? null,
        passed: typeof actual?.[key] === 'number' && Number.isFinite(offsets[key]) && actual[key] === offsets[key] });
    }
  }
  for (const name of ['navigation', 'subresource']) {
    const header = requestHeaders?.[name]?.values?.['accept-language'];
    const first = typeof header === 'string' ? header.split(',')[0].split(';')[0].trim() : null;
    checks.push({ context: name, property: 'accept-language', actual: header ?? null, expected: primary,
      passed: primary !== null && language(first) === primary });
  }
  return checks;
};
