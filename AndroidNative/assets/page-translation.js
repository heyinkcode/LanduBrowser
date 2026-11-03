// Translate text nodes without replacing elements, attributes, or form values.
(function createPageTranslation(key) {
  'use strict';
  const entries = [];
  let characters = 0;
  let truncated = false;
  const excluded = 'script,style,noscript,template,input,textarea,select,option,code,pre,' +
    '[contenteditable]:not([contenteditable="false"]),[translate="no"],.notranslate';
  function eligible(node) {
    const parent = node.parentElement;
    if (!parent || !node.isConnected || parent.closest(excluded) || !node.data.trim()) return false;
    const style = getComputedStyle(parent);
    return style.display !== 'none' && style.visibility !== 'hidden' &&
      parent.getClientRects().length > 0;
  }
  function collect(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (!eligible(node)) continue;
      if (entries.length >= 10000 || characters + node.data.length > 2000000) {
        truncated = true;
        break;
      }
      entries.push({node, original: node.data, translated: null, pending: false});
      characters += node.data.length;
    }
  }
  collect(document.body || document.documentElement);
  let cursor = 0;
  const api = {
    next() {
      const batch = [];
      let size = 0;
      while (cursor < entries.length && batch.length < 16 && size < 12000) {
        const id = cursor++;
        const entry = entries[id];
        if (!eligible(entry.node) || entry.node.data !== entry.original) continue;
        entry.pending = true;
        batch.push({id, text: entry.original.trim()});
        size += entry.original.length;
      }
      return batch;
    },
    apply(batch) {
      let applied = 0;
      for (const item of batch) {
        const entry = entries[item.id];
        if (!entry || !entry.pending || typeof item.text !== 'string') continue;
        entry.pending = false;
        if (!eligible(entry.node) || entry.node.data !== entry.original) continue;
        const prefix = /^\s*/u.exec(entry.original)[0];
        const suffix = /\s*$/u.exec(entry.original)[0];
        entry.translated = prefix + item.text + suffix;
        entry.node.data = entry.translated;
        applied++;
      }
      return applied;
    },
    restore() {
      for (const entry of entries) {
        if (entry.translated !== null && entry.node.isConnected &&
            entry.node.data === entry.translated) entry.node.data = entry.original;
      }
      delete globalThis[key];
      return true;
    }
  };
  Object.defineProperty(globalThis, key, {value: api, configurable: true});
  return {language: document.documentElement.lang, count: entries.length, truncated};
})
