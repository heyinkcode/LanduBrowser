import { parseArgs } from 'node:util';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
const { values } = parseArgs({options:{target:{type:'string'},phase:{type:'string'},version:{type:'string'},fps:{type:'string'},output:{type:'string'},navigate:{type:'boolean',default:false}}});
if(!['baseline','custom','automatic'].includes(values.phase)||!values.output||!path.isAbsolute(values.output)||/^(?:c:[\\/]|\/mnt\/c(?:\/|$))/i.test(values.output))throw Error('Explicit phase and non-C report path required');
const origin='http://127.0.0.1:18766', owned='http://127.0.0.1:18768/ua-raf-check/';
const version=await(await fetch(origin+'/json/version',{signal:AbortSignal.timeout(5000)})).json();
if(version['Android-Package']!=='com.landu.browser')throw Error('Unexpected Android debugger package');
const safe=t=>t.type==='page'&&(t.url==='about:blank'||t.url.startsWith('http://127.0.0.1:18768/')||t.url.startsWith('http://127.0.0.1:18765/')||/^https:\/\/ping0\.cc\/env(?:[?#]|$)/.test(t.url));
const targets=(await(await fetch(origin+'/json/list',{signal:AbortSignal.timeout(5000)})).json()).filter(safe);
const candidates=values.target?targets.filter(t=>t.id===values.target):targets;
let target,socket,id=0;
const pending=new Map();
async function connect(t){const endpoint=new URL(t.webSocketDebuggerUrl);if(endpoint.hostname!=='127.0.0.1'||endpoint.port!=='18766')throw Error('Unexpected debugger socket');socket=new WebSocket(endpoint);const connection=socket;await new Promise((r,j)=>{const timer=setTimeout(()=>j(Error('socket timeout')),5000);socket.addEventListener('open',()=>{clearTimeout(timer);r()},{once:true});socket.addEventListener('error',()=>{clearTimeout(timer);j(Error('socket failed'))},{once:true})});socket.addEventListener('message',e=>{const m=JSON.parse(e.data),p=pending.get(m.id);if(!p)return;pending.delete(m.id);clearTimeout(p.timer);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result)});socket.addEventListener('close',()=>{if(socket!==connection)return;for(const p of pending.values()){clearTimeout(p.timer);p.reject(Error('socket closed'))}pending.clear()})}
function call(method,params={},timeout=8000){return new Promise((resolve,reject)=>{const next=++id;const timer=setTimeout(()=>{pending.delete(next);reject(Error(method+' timed out'))},timeout);pending.set(next,{resolve,reject,timer});socket.send(JSON.stringify({id:next,method,params}))})}
try{
 for(const candidate of candidates){await connect(candidate);const read=await call('Runtime.evaluate',{expression:'({visibility:document.visibilityState,focused:document.hasFocus()})',returnByValue:true});if(read.result.value?.visibility==='visible'){target=candidate;break}socket.close();socket=null;}
 if(!target)throw Error('No visible owned QA/Ping0 page; manual foreground needed. No focus or input action sent.');
 if(values.navigate)await call('Page.navigate',{url:owned});else if(!target.url.startsWith(owned))throw Error('Explicit --navigate needed to replace an existing diagnostic page');
 let result,error;
 const deadline=Date.now()+45000;
 while(Date.now()<deadline){const read=await call('Runtime.evaluate',{expression:'({result:window.__landuUaRafResult,error:window.__landuUaRafError})',returnByValue:true});if(read.result.value?.error){error=read.result.value.error;break}if(read.result.value?.result){result=read.result.value.result;break}await new Promise(r=>setTimeout(r,500))}
 if(!result)throw Error(error||'Native RAF/identity readback did not complete');
 const checks=[...Object.entries(result.checks).map(([name,passed])=>({name,passed:passed===true}))];
 const expected=values.version||version.Browser.replace(/^Chrome\//,'');
 const major=expected.split('.')[0];
 const realms=[result.identity,result.iframe,result.worker.identity,result.serviceWorker];
 checks.push({name:'all-realms-full-version',passed:realms.every(r=>r.high.uaFullVersion===expected)});
 checks.push({name:'all-realms-UA-major',passed:realms.every(r=>r.ua.includes('Chrome/'+major+'.'))});
 const quote=v=>JSON.stringify(v),brands=list=>list.map(b=>quote(b.brand)+';v='+quote(b.version)).join(', ');
 const h=result.headers,m=result.identity;
 const headerExpectations={'sec-ch-ua':brands(m.low.brands),'sec-ch-ua-mobile':m.low.mobile?'?1':'?0','sec-ch-ua-platform':quote(m.low.platform),'sec-ch-ua-platform-version':quote(m.high.platformVersion),'sec-ch-ua-arch':quote(m.high.architecture),'sec-ch-ua-bitness':quote(m.high.bitness),'sec-ch-ua-model':quote(m.high.model),'sec-ch-ua-wow64':m.high.wow64?'?1':'?0','sec-ch-ua-full-version-list':brands(m.high.fullVersionList),'sec-ch-ua-form-factors':m.high.formFactors.map(quote).join(', ')};
 for(const [name,expectedHeader]of Object.entries(headerExpectations))checks.push({name:'HTTP-'+name,passed:h[name]===expectedHeader});
 if(values.fps){const fps=Number(values.fps);if(!Number.isFinite(fps)||fps<1||fps>240)throw Error('Invalid expected FPS');for(const [name,raf]of [['Window',result.raf],['Worker',result.worker.raf]])checks.push({name:name+'-real-RAF-target',passed:!!raf&&Math.abs(1000/raf.mean-fps)<=fps*.2})}
 const report={kind:'physical-device-UA-CH-native-RAF-readback',phase:values.phase,atUtc:new Date().toISOString(),package:'com.landu.browser',browserProduct:version.Browser,targetId:target.id,expectedVersion:expected,expectedFps:values.fps?Number(values.fps):null,passed:checks.every(c=>c.passed),checks,observed:result,focusEmulated:false,inputInjected:false,timestampsOverridden:false};
 await writeFile(values.output,JSON.stringify(report,null,2)+'\n');
 process.stdout.write(JSON.stringify({report:values.output,phase:values.phase,passed:report.passed,checks:checks.length,failures:checks.filter(c=>!c.passed),windowMean:result.raf.mean,workerMean:result.worker.raf?.mean})+'\n');
 if(!report.passed)process.exitCode=1;
}finally{if(socket&&socket.readyState!==WebSocket.CLOSED)await new Promise(resolve=>{const timer=setTimeout(resolve,1500);socket.addEventListener('close',()=>{clearTimeout(timer);resolve()},{once:true});socket.close()})}
