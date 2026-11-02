'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');

const root = path.resolve(__dirname, '..');
const output = path.join(root, 'reports', 'landu-ui-layout');
const results = [];
const measurements = [];
fs.mkdirSync(output, { recursive: true });

function check(name, condition, detail) {
  try {
    assert.ok(condition, JSON.stringify(detail));
    results.push({ name, ok: true });
  } catch (error) {
    results.push({ name, ok: false, detail });
  }
}

function measure(scopeSelector) {
  const scope = document.querySelector(scopeSelector);
  const visible = node => node && node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden';
  const box = node => {
    const rect = node.getBoundingClientRect();
    return { id: node.id || node.closest('.themed-select')?.querySelector('select')?.id || node.className, top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right, height: rect.height, width: rect.width };
  };
  const field = label => [...label.querySelectorAll('.themed-select-button, input:not([type="checkbox"]):not([type="radio"]):not([type="hidden"]):not([type="file"]):not(.themed-select-native)')].find(visible);
  const rows = [];
  for (const grid of [...scope.querySelectorAll('.form-two,.form-three,.privacy-grid,.proxy-host-row')].filter(visible)) {
    const columns = getComputedStyle(grid).gridTemplateColumns.split(' ').length;
    const children = [...grid.children].filter(visible);
    for (let index = 0; index < children.length; index += columns) {
      const controls = children.slice(index, index + columns).map(field).filter(Boolean).map(box);
      if (controls.length > 1) rows.push(controls);
    }
  }
  const controls = [...scope.querySelectorAll('input:not([type="checkbox"]):not([type="radio"]):not([type="hidden"]):not([type="file"]):not([type="range"]):not([type="color"]):not(.themed-select-native), .themed-select-button')].filter(visible).map(box);
  const inlineControls = [...scope.querySelectorAll('.ua-tools > button,.ua-tools .ua-ver input,.storage-actions > button,.editor-actions > button,.dialog-actions > button,.store-source-tabs > button,label > div:has(> input) > button,.proxy-host-row > button')].filter(visible).map(box);
  const segments = [...scope.querySelectorAll('.segmented')].filter(visible).map(group => {
    const outer = group.getBoundingClientRect();
    return { id: group.id, items: [...group.children].filter(visible).map(label => {
      const rect = label.getBoundingClientRect();
      return { height: rect.height, topInset: rect.top - outer.top, bottomInset: outer.bottom - rect.bottom };
    }) };
  });
  const checkboxRows = [...scope.querySelectorAll('.form-two > .check-row')].filter(visible).map(label => {
    const grid = label.parentElement;
    const adjacent = [...grid.children].map(field).find(Boolean);
    const input = label.querySelector('input');
    const text = [...label.childNodes].find(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim());
    if (!adjacent || !input || !text) return null;
    const rect = input.getBoundingClientRect();
    const other = adjacent.getBoundingClientRect();
    return { id: input.id, centerOffset: (rect.top + rect.bottom - other.top - other.bottom) / 2 };
  }).filter(Boolean);
  const boundary = scope.getBoundingClientRect();
  const overflow = [...scope.querySelectorAll('input:not(.themed-select-native), textarea, .themed-select, .segmented')].filter(visible).map(box).filter(rect => rect.left < boundary.left - 1 || rect.right > boundary.right + 1);
  return { rows, controls, inlineControls, segments, checkboxRows, overflow, pageOverflow: document.documentElement.scrollWidth > innerWidth + 1 };
}

function validate(prefix, metrics) {
  measurements.push({ prefix, ...metrics });
  check(prefix + ' / field heights', metrics.controls.every(control => Math.abs(control.height - 34) <= 1), metrics.controls.filter(control => Math.abs(control.height - 34) > 1));
  check(prefix + ' / action heights', metrics.inlineControls.every(control => Math.abs(control.height - 34) <= 1), metrics.inlineControls.filter(control => Math.abs(control.height - 34) > 1));
  const unaligned = metrics.rows.filter(row => Math.max(...row.map(control => control.top)) - Math.min(...row.map(control => control.top)) > 1 || Math.max(...row.map(control => control.bottom)) - Math.min(...row.map(control => control.bottom)) > 1);
  check(prefix + ' / grid alignment', unaligned.length === 0, unaligned);
  check(prefix + ' / segment centering', metrics.segments.every(group => group.items.every(item => Math.abs(item.topInset - item.bottomInset) <= 1)), metrics.segments);
  check(prefix + ' / inline checkbox centering', metrics.checkboxRows.every(row => Math.abs(row.centerOffset) <= 1), metrics.checkboxRows);
  check(prefix + ' / field containment', metrics.overflow.length === 0 && !metrics.pageOverflow, { overflow: metrics.overflow, pageOverflow: metrics.pageOverflow });
}

async function main() {
  const launch = { headless: true, args: ['--allow-file-access-from-files'] };
  if (process.env.UI_AUDIT_BROWSER) launch.executablePath = process.env.UI_AUDIT_BROWSER;
  else if (process.platform === 'win32') launch.channel = 'msedge';
  const browser = await chromium.launch(launch);
  try {
    const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, locale: 'zh-CN' });
    const bridge = fs.readFileSync(path.join(__dirname, 'ui-audit-bridge.js'), 'utf8');
    await context.addInitScript({ content: bridge + "\nwindow.ops.getInfo = async () => ({ platform: 'win32', appVersion: '1.0.25', browsers: [], profileRoot: 'UI-AUDIT-FIXTURE' }); localStorage.setItem('openbrowser-ui-skin-v1', 'landu-apple');" });
    const page = await context.newPage();
    const frame = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.goto(pathToFileURL(path.join(root, 'index.html')).href);
    await page.waitForFunction(() => typeof openProfileEditor === 'function');
    await page.evaluate(() => {
      applyUiTheme('landu-apple');
      ui.profiles = [normalizeProfileSettings({ id: 'layout-fixture', number: 1, title: '排版复查', os: 'Windows', language: 'en-US', networkMode: 'direct', proxy: 'Direct' })];
      openProfileEditor('layout-fixture');
    });

    for (const viewport of [{ width: 1600, height: 1000 }, { width: 1280, height: 900 }, { width: 1000, height: 800 }, { width: 900, height: 650 }]) {
      await page.setViewportSize(viewport);
      for (const tab of ['basic', 'proxy', 'privacy', 'advanced']) {
        await page.evaluate(tab => { setEditorTab(tab); document.querySelector('main').scrollTop = 0; }, tab);
        await frame();
        validate(`${viewport.width}x${viewport.height}/${tab}`, await page.evaluate(measure, '#profile-editor-form'));
        if (viewport.width === 1600) await page.screenshot({ path: path.join(output, `${tab}.png`) });
        const reachable = await page.evaluate(() => {
          const panel = document.querySelector('.editor-panel.active');
          const last = [...panel.querySelectorAll('input:not([type="hidden"]):not(.themed-select-native),textarea,.themed-select-button,button')].filter(node => node.getClientRects().length).at(-1);
          last?.scrollIntoView({ block: 'center' });
          return last?.id;
        });
        await frame();
        const unobscured = await page.evaluate(id => {
          const field = document.getElementById(id);
          if (!field) return true;
          const rect = field.getBoundingClientRect();
          const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
          return field === hit || field.contains(hit);
        }, reachable);
        check(`${viewport.width}/${tab} / last control reachable`, unobscured, reachable);
        if (tab === 'privacy' && viewport.width === 1600) {
          await page.locator('#editor-stability-hamming').scrollIntoViewIfNeeded();
          await page.screenshot({ path: path.join(output, 'privacy-stability.png') });
        }
      }

      await page.evaluate(() => {
        setEditorTab('proxy');
        document.querySelector('#editor-network-mode input[value="custom"]').click();
      });
      await frame();
      validate(`${viewport.width}/proxy-expanded`, await page.evaluate(measure, '#profile-editor-form'));
      await page.evaluate(() => {
        setEditorTab('privacy');
        for (const [id, value] of [['editor-resolution', 'custom'], ['editor-font-mode', 'custom'], ['editor-timezone-mode', 'custom'], ['editor-geo-mode', 'custom']]) {
          const field = document.getElementById(id);
          field.value = value;
          field.dispatchEvent(new Event('change', { bubbles: true }));
        }
      });
      await frame();
      validate(`${viewport.width}/custom-fields`, await page.evaluate(measure, '#profile-editor-form'));

      for (const id of ['proxy-dialog', 'group-dialog', 'add-app-dialog', 'profile-dialog', 'batch-add-dialog', 'sync-settings-dialog']) {
        await page.evaluate(id => document.getElementById(id).showModal(), id);
        await frame();
        validate(`${viewport.width}/${id}`, await page.evaluate(measure, '#' + id));
        const dialog = await page.evaluate(id => {
          const node = document.getElementById(id);
          const close = node.querySelector('.dialog-head > button').getBoundingClientRect();
          node.scrollTop = node.scrollHeight;
          const buttons = [...node.querySelectorAll('.dialog-actions > button')].map(button => button.getBoundingClientRect());
          const rect = node.getBoundingClientRect();
          return { close: [close.width, close.height], fits: rect.top >= 0 && rect.bottom <= innerHeight + 1, actionsReachable: buttons.every(button => button.bottom <= Math.min(rect.bottom, innerHeight) + 1), assignRows: [...node.querySelectorAll('.assign-item')].map(row => getComputedStyle(row).flexDirection) };
        }, id);
        check(`${viewport.width}/${id} / close button square`, Math.abs(dialog.close[0] - 32) <= 1 && Math.abs(dialog.close[1] - 32) <= 1, dialog);
        check(`${viewport.width}/${id} / footer reachable`, dialog.fits && dialog.actionsReachable, dialog);
        check(`${viewport.width}/${id} / inline checkboxes`, dialog.assignRows.every(direction => direction === 'row'), dialog);
        if (id === 'sync-settings-dialog') {
          const offsets = await page.evaluate(() => [...document.querySelectorAll('#sync-settings-dialog .sync-setting-grid > label')].map(label => {
            const title = label.firstElementChild.getBoundingClientRect();
            const control = label.querySelector('input,.inline-checks').getBoundingClientRect();
            return (title.top + title.bottom - control.top - control.bottom) / 2;
          }));
          check(`${viewport.width}/${id} / labels and checkboxes on one line`, offsets.every(offset => Math.abs(offset) <= 2), offsets);
        }
        if (viewport.width === 1600) {
          await page.evaluate(id => { document.getElementById(id).scrollTop = 0; }, id);
          await page.screenshot({ path: path.join(output, id + '.png') });
        }
        await page.evaluate(id => document.getElementById(id).close(), id);
      }
    }

    for (const viewport of [{ width: 1600, height: 1000 }, { width: 900, height: 650 }]) {
      await page.setViewportSize(viewport);
      await page.evaluate(() => { switchView('profiles'); setThemePopoverOpen(true); });
      await frame();
      const theme = await page.evaluate(() => {
        const badge = document.querySelector('#engine-badge').getBoundingClientRect();
        const trigger = document.querySelector('#theme-trigger').getBoundingClientRect();
        const panel = document.querySelector('#theme-popover');
        const bounds = panel.getBoundingClientRect();
        const cards = [...panel.querySelectorAll('.theme-option')].map(card => ({ height: card.getBoundingClientRect().height, border: getComputedStyle(card).borderTopWidth, shadow: getComputedStyle(card).boxShadow }));
        return { badgeHeight: badge.height, triggerHeight: trigger.height, centerOffset: (badge.top + badge.bottom - trigger.top - trigger.bottom) / 2, fits: bounds.top >= 0 && bounds.bottom <= innerHeight + 1 && bounds.right <= innerWidth + 1, cards, paginationBorder: getComputedStyle(document.querySelector('#profile-pagination')).borderLeftWidth };
      });
      check(`${viewport.width}/header / matching controls`, Math.abs(theme.badgeHeight - theme.triggerHeight) <= 1 && Math.abs(theme.centerOffset) <= 1, theme);
      check(`${viewport.width}/theme / contained panel and consistent rows`, theme.fits && theme.cards.every(card => card.height === 64 && card.border === '1px' && card.shadow === 'none'), theme);
      check(`${viewport.width}/pagination / no orphan divider`, theme.paginationBorder === '0px', theme);
      if (viewport.width === 1600) await page.screenshot({ path: path.join(output, 'theme-picker.png') });
      await page.evaluate(() => setThemePopoverOpen(false));
    }
    for (const viewport of [{ width: 1280, height: 900 }, { width: 900, height: 650 }]) {
      await page.setViewportSize(viewport);
      for (const view of ['profiles', 'groups', 'proxies', 'extensions', 'sync', 'api-mcp', 'logs', 'system']) {
        await page.evaluate(view => { switchView(view); document.querySelector('main').scrollTop = 0; }, view);
        await frame();
        const overview = await page.evaluate(view => {
          const node = document.getElementById('view-' + view);
          const bounds = node.getBoundingClientRect();
          const main = document.querySelector('main').getBoundingClientRect();
          const clipped = [...node.querySelectorAll('input:not(.themed-select-native):not([type="checkbox"]):not([type="radio"]), textarea, .themed-select')].filter(node => node.getClientRects().length && !node.closest('.table-card')).filter(node => { const rect = node.getBoundingClientRect(); return rect.left < main.left - 1 || rect.right > main.right + 1; }).map(node => node.id || node.className);
          return { fits: bounds.left >= main.left - 1 && bounds.right <= main.right + 1, clipped };
        }, view);
        check(`${viewport.width}/${view} / page containment`, overview.fits && overview.clipped.length === 0, overview);
        if (view === 'system') validate(`${viewport.width}/system-fields`, await page.evaluate(measure, '#view-system .settings-grid'));
        if (viewport.width === 1280) await page.screenshot({ path: path.join(output, 'view-' + view + '.png') });
      }
    }
    check('renderer / no runtime errors', (await page.evaluate(() => window.__uiAuditErrors)).length === 0, await page.evaluate(() => window.__uiAuditErrors));
    const failures = results.filter(result => !result.ok);
    fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify({ results, measurements }, null, 2));
    console.log(JSON.stringify({ checks: results.length, passed: results.length - failures.length, failures, output }, null, 2));
    if (failures.length) process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
