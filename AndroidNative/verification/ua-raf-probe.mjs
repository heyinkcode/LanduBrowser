// Owned loopback probe: validates real headers, metadata and native callbacks.
import http from 'node:http';
import { parseArgs } from 'node:util';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const { values } = parseArgs({ options: { port: {type:'string',default:'18768'}, output:{type:'string'} } });
const port = Number(values.port);
if (!Number.isInteger(port) || port < 1024 || port > 65535 || !values.output || !path.isAbsolute(values.output)
    || /^(?:c:[\\/]|\/mnt\/c(?:\/|$))/i.test(values.output)) throw Error('Use an authorized non-C report path and a loopback port');
await mkdir(path.dirname(values.output), {recursive:true});
const records = [];
const uaRead = `async function identity(){return {ua:navigator.userAgent,platform:navigator.platform,low:navigator.userAgentData?.toJSON(),high:await navigator.userAgentData?.getHighEntropyValues(['architecture','bitness','model','platformVersion','fullVersionList','uaFullVersion','wow64','formFactors'])}}`;
const rafRead = `async function frames(){
 const seen=[]; let cancelled=false; const id=requestAnimationFrame(()=>{cancelled=true});cancelAnimationFrame(id);
 const timestamps=await new Promise((resolve,reject)=>{const limit=setTimeout(()=>reject(Error('RAF timed out')),15000);function next(t){seen.push(t);if(seen.length===61){clearTimeout(limit);resolve(seen)}else requestAnimationFrame(next)}requestAnimationFrame(next)});
 const diffs=timestamps.slice(1).map((t,i)=>t-timestamps[i]), sorted=[...diffs].sort((a,b)=>a-b);
 return {cancelledCallbackInvoked:cancelled,strictlyIncreasing:diffs.every(d=>d>0),count:diffs.length,mean:diffs.reduce((a,b)=>a+b,0)/diffs.length,median:sorted[30],min:sorted[0],max:sorted.at(-1),timestamps};
}`;
const worker = `${uaRead}\n${rafRead}\nonmessage=async()=>{try{postMessage({identity:await identity(),raf:typeof requestAnimationFrame==='function'?await frames():null})}catch(e){postMessage({error:String(e)})}};`;
const serviceWorker = `${uaRead}\nself.addEventListener('install',e=>e.waitUntil(self.skipWaiting()));self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));self.addEventListener('message',e=>e.waitUntil(identity().then(value=>e.ports[0].postMessage(value))));`;
const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>岚渡 UA-CH / RAF 原生验收</title>
<style>body{font:16px/1.6 system-ui;margin:24px;color:#202632;background:#f6f8fb}main{background:white;border-radius:16px;padding:24px;max-width:900px;margin:auto}h1{font-size:22px;font-weight:500}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px}iframe{width:100%;height:80px;border:1px solid #e3e7ef;border-radius:12px}</style>
<main><h1>UA-CH 与 RAF 原生验收</h1><p id="status">保持此页面在前台，正在采集真实读数…</p><pre id="result"></pre><iframe id="frame" src="/frame"></iframe></main>
<script>${uaRead}\n${rafRead}
async function fromWorker(){const w=new Worker('/worker.js');return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{w.terminate();reject(Error('Worker timed out'))},20000);w.onmessage=e=>{clearTimeout(timer);w.terminate();resolve(e.data)};w.onerror=e=>{clearTimeout(timer);w.terminate();reject(Error(e.message))};w.postMessage('read')})}
async function fromServiceWorker(){await navigator.serviceWorker.register('/ua-raf-check/sw.js',{scope:'/ua-raf-check/'});const registration=await navigator.serviceWorker.ready;return new Promise((resolve,reject)=>{const channel=new MessageChannel();const timer=setTimeout(()=>reject(Error('Service worker timed out')),10000);channel.port1.onmessage=e=>{clearTimeout(timer);channel.port1.close();resolve(e.data)};registration.active.postMessage('read',[channel.port2])})}
(async()=>{try{
 const frame=document.getElementById('frame');if(typeof frame.contentWindow.readIdentity!=='function')await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('iframe timed out')),10000);function ready(){if(typeof frame.contentWindow.readIdentity==='function'){clearTimeout(timer);frame.removeEventListener('load',ready);resolve()}}frame.addEventListener('load',ready);ready()});
 const visibleStart=document.visibilityState;
 const result={startedAt:new Date().toISOString(),visibleStart,identity:await identity(),iframe:await frame.contentWindow.readIdentity(),headers:await(await fetch('/echo')).json()};
 [result.raf,result.worker,result.serviceWorker]=await Promise.all([frames(),fromWorker(),fromServiceWorker()]);
 result.visibleEnd=document.visibilityState;result.focused=document.hasFocus();result.finishedAt=new Date().toISOString();
 const metadataSame=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
 result.checks={foreground:result.visibleStart==='visible'&&result.visibleEnd==='visible',headerUA:result.headers['user-agent']===result.identity.ua,iframeIdentity:metadataSame(result.identity,result.iframe),workerIdentity:metadataSame(result.identity,result.worker.identity),serviceWorkerIdentity:metadataSame(result.identity,result.serviceWorker),windowCancellation:result.raf.cancelledCallbackInvoked===false,windowTimestamp:result.raf.strictlyIncreasing,workerCancellation:result.worker.raf?.cancelledCallbackInvoked===false,workerTimestamp:result.worker.raf?.strictlyIncreasing===true};
 window.__landuUaRafResult=result;
 document.getElementById('status').textContent=Object.values(result.checks).every(Boolean)?'基础一致性检查通过；请继续核对保存的自定义值与目标帧率。':'发现待检查项，见下方真实读数。';
 document.getElementById('result').textContent=JSON.stringify(result,null,2);await fetch('/result',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(result)});
}catch(error){document.getElementById('status').textContent='采集失败：'+error;window.__landuUaRafError=String(error)}})();</script></html>`;
const server = http.createServer(async (request,response)=>{
  response.setHeader('Cache-Control','no-store');
  response.setHeader('Accept-CH','Sec-CH-UA-Full-Version-List, Sec-CH-UA-Platform-Version, Sec-CH-UA-Arch, Sec-CH-UA-Bitness, Sec-CH-UA-Model, Sec-CH-UA-WoW64, Sec-CH-UA-Form-Factors');
  const url = new URL(request.url, `http://127.0.0.1:${port}`);
  if(url.pathname==='/echo'){response.setHeader('Content-Type','application/json');response.end(JSON.stringify(Object.fromEntries(Object.entries(request.headers).filter(([key])=>key==='user-agent'||key.startsWith('sec-ch-ua')))));return;}
  if(url.pathname==='/worker.js'||url.pathname==='/ua-raf-check/sw.js'){response.setHeader('Content-Type','text/javascript');response.end(url.pathname==='/worker.js'?worker:serviceWorker);return;}
  if(url.pathname==='/result' && request.method==='POST'){
    let size=0;const chunks=[];
    for await(const chunk of request){size+=chunk.length;if(size>65536){response.writeHead(413).end();return;}chunks.push(chunk);}
    try{records.push(JSON.parse(Buffer.concat(chunks).toString('utf8')));await writeFile(values.output,JSON.stringify({kind:'owned-phone-UA-CH-RAF-probe',records},null,2)+'\n');response.end('saved');}
    catch{response.writeHead(400).end('Invalid result');}return;
  }
  if(url.pathname==='/frame'){response.setHeader('Content-Type','text/html; charset=utf-8');response.end('<!doctype html><meta charset="utf-8"><p>同源 iframe 原生身份检查</p><script>'+uaRead+';window.readIdentity=identity;</script>');return;}
  if(url.pathname!=='/ua-raf-check/'){response.writeHead(404).end('Not found');return;}
  response.setHeader('Content-Type','text/html; charset=utf-8');response.end(html);
});
server.listen(port,'127.0.0.1',()=>process.stdout.write(JSON.stringify({listening:`http://127.0.0.1:${port}/ua-raf-check/`,output:values.output})+'\n'));
process.on('SIGINT',()=>server.close());process.on('SIGTERM',()=>server.close());
