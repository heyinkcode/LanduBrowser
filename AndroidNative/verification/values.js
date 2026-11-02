globalThis.landuReadHardware = () => {
  const regional = Intl.DateTimeFormat().resolvedOptions();
  return {
  hardwareConcurrency: navigator.hardwareConcurrency,
  deviceMemory: typeof navigator.deviceMemory === 'number' ? navigator.deviceMemory : null,
  language: navigator.language,
  languages: Array.from(navigator.languages || []),
  userAgent: navigator.userAgent,
  timezone: regional.timeZone,
  intlLocale: regional.locale,
  numberLocale: Intl.NumberFormat().resolvedOptions().locale,
  timezoneOffsetJanuary: new Date(Date.UTC(2026, 0, 15, 12)).getTimezoneOffset(),
  timezoneOffsetJuly: new Date(Date.UTC(2026, 6, 15, 12)).getTimezoneOffset(),
  secureContext: globalThis.isSecureContext,
  };
};

globalThis.landuReadDnt = async () => {
  const supported = 'doNotTrack' in navigator;
  const value = supported ? navigator.doNotTrack : null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch('request-headers', { cache: 'no-store', signal: controller.signal });
    if (!response.ok) throw new Error(`请求头检查失败：HTTP ${response.status}`);
    return { supported, value, headers: await response.json() };
  } finally { clearTimeout(timer); }
};
