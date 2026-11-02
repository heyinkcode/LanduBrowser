(() => {
  const canonicalVersion = value => {
    if (typeof value !== 'string' || !/^[1-9][0-9](\.(0|[1-9][0-9]{0,2})){0,2}$/.test(value)) return null;
    const parts = value.split('.');
    return parts[0] + '.' + (parts[1] ?? '0') + '.' + (parts[2] ?? '0');
  };
  const brands = list => {
    if (!Array.isArray(list) || !list.length || list.some(item => !item || typeof item.brand !== 'string'
      || !item.brand || typeof item.version !== 'string' || !item.version)) return null;
    if (new Set(list.map(item => item.brand)).size !== list.length) return null;
    return JSON.stringify(list.map(({ brand, version }) => ({ brand, version }))
      .sort((a, b) => a.brand < b.brand ? -1 : a.brand > b.brand ? 1 : 0));
  };
  const quoted = text => {
    if (typeof text !== 'string') return undefined;
    try { const value = JSON.parse(text); return typeof value === 'string' ? value : undefined; }
    catch { return undefined; }
  };
  const headerBrands = text => {
    if (typeof text !== 'string') return null;
    const pattern = /("(?:[^"\\]|\\.)*")\s*;\s*v=("(?:[^"\\]|\\.)*")/g;
    const list = []; let end = 0;
    for (const match of text.matchAll(pattern)) {
      if (text.slice(end, match.index).trim() !== (list.length ? ',' : '')) return null;
      const brand = quoted(match[1]), version = quoted(match[2]);
      if (brand === undefined || version === undefined) return null;
      list.push({ brand, version }); end = match.index + match[0].length;
    }
    return text.slice(end).trim() ? null : brands(list);
  };

  globalThis.landuParseExpectedAndroidIdentity = (mode, version, model) => {
    if (mode === 'coherence') return { platformVersion: null, model: null };
    if (mode !== 'custom' || typeof version !== 'string' || typeof model !== 'string'
        || /[\x00-\x1f\x7f]/.test(version + model) || model.length > 80) throw new Error('请填写有效的 Android 身份预期值');
    const normalized = canonicalVersion(version.trim());
    if (!normalized || !/^[A-Za-z0-9 ._-]{0,80}$/.test(model.trim())) throw new Error('Android 版本或型号预期值无效');
    return { platformVersion: normalized, model: model.trim() };
  };

  globalThis.landuReadBrowserIdentity = async () => {
    const data = navigator.userAgentData;
    if (!data || typeof data.getHighEntropyValues !== 'function') throw new Error('User-Agent Client Hints API 不可用');
    const low = { brands: data.brands.map(({ brand, version }) => ({ brand, version })), mobile: data.mobile, platform: data.platform };
    const highPromise = data.getHighEntropyValues(['platformVersion', 'model', 'fullVersionList', 'architecture', 'bitness', 'wow64']);
    const controller = new AbortController();
    let timer;
    try {
      const headersPromise = fetch('request-headers', { cache: 'no-store', signal: controller.signal }).then(response => {
        if (!response.ok) throw new Error(`请求头检查失败：HTTP ${response.status}`);
        return response.json();
      });
      const timeout = new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('浏览器身份读取超时')), 12000); });
      const [high, headers] = await Promise.race([Promise.all([highPromise, headersPromise]), timeout]);
      return { userAgent: navigator.userAgent, platform: navigator.platform, low, high, headers };
    } finally { clearTimeout(timer); controller.abort(); }
  };

  globalThis.landuIdentityChecks = (contexts, navigation, expected, kernelVersion) => {
    if (typeof kernelVersion !== 'string' || !/^[1-9][0-9]*\.[0-9]+\.[0-9]+\.[0-9]+$/.test(kernelVersion)) {
      throw new Error('缺少有效的固定内核版本参考，不能宣布身份检查通过');
    }
    const reference = contexts.main?.values;
    const major = kernelVersion.split('.')[0];
    const platform = expected.platformVersion !== null ? 'Android' : reference?.low?.platform;
    const version = expected.platformVersion ?? reference?.high?.platformVersion;
    const model = expected.model ?? reference?.high?.model;
    const lowBrands = brands(reference?.low?.brands);
    const fullBrands = brands(reference?.high?.fullVersionList);
    const mobile = reference?.low?.mobile;
    const checks = [];
    const add = (context, property, actual, want, passed) => checks.push({ context, property,
      actual: actual ?? null, expected: want ?? null, passed: !!passed });
    for (const name of ['main', 'iframe', 'dedicatedWorker', 'serviceWorker']) {
      const value = contexts[name]?.values, low = value?.low, high = value?.high;
      add(name, 'userAgent', value?.userAgent, reference?.userAgent,
        typeof value?.userAgent === 'string' && value.userAgent === reference?.userAgent
          && /Chrome\/([0-9]+)\./.exec(value.userAgent)?.[1] === major);
      add(name, 'navigator.platform', value?.platform, reference?.platform,
        typeof value?.platform === 'string' && value.platform.length > 0 && value.platform === reference?.platform);
      add(name, 'platform', low?.platform, platform, typeof platform === 'string' && !!platform && low?.platform === platform);
      add(name, 'high.platform', high?.platform, platform, typeof platform === 'string' && !!platform && high?.platform === platform);
      add(name, 'mobile', low?.mobile, mobile, typeof mobile === 'boolean' && low?.mobile === mobile && high?.mobile === mobile);
      add(name, 'brands', low?.brands, reference?.low?.brands,
        lowBrands !== null && brands(low?.brands) === lowBrands && brands(high?.brands) === lowBrands
          && low.brands.some(item => item.brand === 'Chromium' && item.version === major));
      add(name, 'fullVersionList', high?.fullVersionList, reference?.high?.fullVersionList,
        fullBrands !== null && brands(high?.fullVersionList) === fullBrands
          && high.fullVersionList.some(item => item.brand === 'Chromium' && item.version === kernelVersion));
      add(name, 'platformVersion', high?.platformVersion, version, typeof version === 'string' && high?.platformVersion === version);
      add(name, 'model', high?.model, model, typeof model === 'string' && high?.model === model);
      for (const property of ['architecture', 'bitness']) {
        add(name, property, high?.[property], reference?.high?.[property], typeof high?.[property] === 'string'
          && high[property] === reference?.high?.[property]);
      }
      add(name, 'wow64', high?.wow64, reference?.high?.wow64,
        typeof high?.wow64 === 'boolean' && high.wow64 === reference?.high?.wow64);
      if (platform === 'Android') {
        const ua = /Android ([0-9.]+)(?:; ([^)]*))?\)/.exec(value?.userAgent ?? '');
        const frozen = ua?.[1] === '10' && ua?.[2] === 'K';
        add(name, 'legacy-UA Android identity', ua?.[0], 'Chromium frozen UA or the selected version/model',
          frozen || (!!ua && canonicalVersion(ua[1]) === version && (ua[2] ?? '') === model));
      }
    }
    for (const name of ['main', 'iframe', 'dedicatedWorker', 'serviceWorker', 'navigation']) {
      const headers = name === 'navigation' ? navigation : contexts[name]?.values?.headers;
      add(name, 'header:User-Agent', headers?.['user-agent'], reference?.userAgent,
        typeof reference?.userAgent === 'string' && headers?.['user-agent'] === reference.userAgent);
      // Pinned Chromium WorkerFetchContext::AddAdditionalRequestHeaders has no
      // Client Hints permissions policy and does not add UA hints to worker
      // fetches. Preserve this native omission; never fabricate sensitive hints.
      // Require the echo's explicit null fields, so incomplete evidence cannot
      // be confused with a fully observed native omission. Partially present
      // hint sets still go through the strict value checks below.
      const worker = name === 'dedicatedWorker' || name === 'serviceWorker';
      const hintKeys = ['sec-ch-ua', 'sec-ch-ua-full-version-list', 'sec-ch-ua-mobile',
        'sec-ch-ua-platform', 'sec-ch-ua-platform-version', 'sec-ch-ua-model'];
      if (worker && hintKeys.every(key => headers?.[key] === null)) {
        checks.push({ context: name, property: 'header:UA Client Hints native omission',
          actual: 'all explicitly absent', expected: 'native worker fetch may omit the complete set',
          coverage: 'not-sent; worker JS identity checked separately', passed: true });
        continue;
      }
      add(name, 'header:Sec-CH-UA', headers?.['sec-ch-ua'], reference?.low?.brands,
        lowBrands !== null && headerBrands(headers?.['sec-ch-ua']) === lowBrands);
      add(name, 'header:Sec-CH-UA-Full-Version-List', headers?.['sec-ch-ua-full-version-list'], reference?.high?.fullVersionList,
        fullBrands !== null && headerBrands(headers?.['sec-ch-ua-full-version-list']) === fullBrands);
      add(name, 'header:Sec-CH-UA-Mobile', headers?.['sec-ch-ua-mobile'], typeof mobile === 'boolean' ? (mobile ? '?1' : '?0') : null,
        typeof mobile === 'boolean' && headers?.['sec-ch-ua-mobile'] === (mobile ? '?1' : '?0'));
      for (const [property, want] of [['platform', platform], ['platform-version', version], ['model', model]]) {
        add(name, 'header:Sec-CH-UA-' + property, headers?.['sec-ch-ua-' + property], want,
          typeof want === 'string' && quoted(headers?.['sec-ch-ua-' + property]) === want);
      }
    }
    return checks;
  };
})();
