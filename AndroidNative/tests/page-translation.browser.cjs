const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const cdp = require('../../Browserapp/cdp.js');
const port = Number(process.argv[2] || 9222);
const source = fs.readFileSync(path.join(__dirname, '../assets/page-translation.js'), 'utf8');
const html = `<!doctype html><html lang="en"><body>
<h1>Welcome to our website</h1><p id="intro"> Please sign in to continue. </p>
<form id="form" action="/accepted" method="post"><label>Email address</label>
<input id="email" name="email" value="dummy@example.invalid"><textarea id="notes">keep text</textarea>
<select id="select"><option value="one">First option</option></select>
<button id="submit">Sign in</button></form><a id="link" href="/next">Learn more</a>
<div contenteditable="true">Editable text</div><p translate="no">Do not translate</p>
<p style="display:none">Hidden text</p><code>const value = true;</code>
<p id="changing">Initial status</p><p id="removed">Temporary text</p>
<script>window.clicks=0;window.submits=0;window.originalButton=document.querySelector('#submit');
originalButton.addEventListener('click',()=>clicks++);
document.querySelector('#form').addEventListener('submit',e=>{e.preventDefault();submits++});</script>
</body></html>`;
(async () => {
  const version = await cdp.json(`http://127.0.0.1:${port}/json/version`);
  const created = await cdp.call(version.webSocketDebuggerUrl, 'Target.createTarget', {url: 'about:blank'});
  const tabs = await cdp.json(`http://127.0.0.1:${port}/json/list`);
  const tab = tabs.find(item => item.id === created.targetId);
  assert(tab, 'The owned test tab must be discoverable');
  const ws = tab.webSocketDebuggerUrl;
  async function evaluate(expression, contextId) {
    const result = await cdp.call(ws, 'Runtime.evaluate', {expression, contextId, returnByValue: true});
    assert.equal(result.exceptionDetails, undefined);
    return result.result.value;
  }
  try {
    await evaluate(`document.open();document.write(${JSON.stringify(html)});document.close()`);
    const {frameTree} = await cdp.call(ws, 'Page.getFrameTree');
    const {executionContextId} = await cdp.call(ws, 'Page.createIsolatedWorld', {
      frameId: frameTree.frame.id, worldName: 'landu-translation-regression'
    });
    const before = await evaluate(`({url:location.href, email:email.value, notes:notes.value, option:select.value,
      action:document.querySelector('#form').action, href:link.href})`);
    const info = await evaluate(`${source}("__qaTranslation")`, executionContextId);
    assert.equal(info.language, 'en');
    assert.equal(await evaluate(`typeof globalThis.__qaTranslation`), 'undefined');
    const batch = await evaluate(`__qaTranslation.next()`, executionContextId);
    const texts = batch.map(item => item.text);
    for (const excluded of ['keep text', 'First option', 'Editable text', 'Do not translate', 'Hidden text', 'const value = true;']) {
      assert(!texts.includes(excluded), excluded);
    }
    await evaluate(`document.querySelector('#changing').firstChild.data='Updated status';document.querySelector('#removed').remove()`);
    await evaluate(`__qaTranslation.apply(${JSON.stringify(batch.map(item => ({id:item.id,text:'中文译文'})))})`, executionContextId);
    const after = await evaluate(`({url:location.href, email:email.value, notes:notes.value, option:select.value,
      action:document.querySelector('#form').action, href:link.href})`);
    assert.deepEqual(after,before);
    const behavior = await evaluate(`originalButton.click();({same:originalButton===document.querySelector('#submit'),
      clicks,submits,changed:document.querySelector('#changing').textContent,space:document.querySelector('#intro').textContent})`);
    assert.deepEqual(behavior,{same:true,clicks:1,submits:1,changed:'Updated status',space:' 中文译文 '});
    await evaluate(`__qaTranslation.restore()`, executionContextId);
    assert.equal(await evaluate(`document.querySelector('#intro').textContent`),' Please sign in to continue. ');
    const receipt = {passed:true, checks:['same URL','same form values','same actions and links',
      'same DOM button identity','click listener','submit listener','editable and hidden exclusions',
      'stale text protection','detached node protection','whitespace','restore original','isolated execution state'],
      translationEngine:'fixture replacements only; ML Kit runtime needs separate device acceptance'};
    console.log(JSON.stringify(receipt));
  } finally {
    await cdp.call(version.webSocketDebuggerUrl, 'Target.closeTarget', {targetId: tab.id});
  }
})().catch(error=>{console.error(error);process.exitCode=1});
