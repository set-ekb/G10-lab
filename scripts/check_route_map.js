// Regression checks for out-of-order router replies and repeated GPS callbacks.
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const html = fs.readFileSync('app/src/main/assets/route_map.html', 'utf8');
const code = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)].map(m => m[1]).filter(Boolean).pop();
function element() {
  return { children: [], value: '', textContent: '', listeners: {}, classList: { toggle() {} },
    addEventListener(name, fn) { this.listeners[name] = fn; }, appendChild(c) { this.children.push(c); },
    set innerHTML(v) { this.children = []; } };
}
const elements = Object.fromEntries(['hint','choices','address','search','locate','rebuild'].map(id => [id,element()]));
const handlers = {}, requests = [], ready = [], errors = [], timers = new Map();
let now = 10000, timerId = 0;
const map = { setView() { return this; }, on(n,f) { handlers[n] = f; }, removeLayer() {}, fitBounds() {}, invalidateSize() {} };
function layer() { return { addTo() { return this; }, on() {}, setStyle() {} }; }
const window = { G10Route: { onMapReady() {}, onRouteCleared() {}, onRouteReady(...a) { ready.push(a); }, onRouteError(e) { errors.push(e); } } };
window.L = { map: () => map, control: { zoom: () => layer() }, tileLayer: () => layer(), circleMarker: () => layer(),
  latLng: (lat,lng) => ({ lat,lng }), geoJSON: () => layer(), featureGroup: () => ({ getBounds: () => ({ pad() { return {}; } }) }) };
const context = { window, L:window.L, document: {getElementById:id=>elements[id],createElement:element},
  localStorage: {getItem(){return null;},setItem(){}}, Date:{now:()=>now}, AbortController,
  setTimeout(fn,ms) { const id=++timerId; timers.set(id,{fn,time:now+ms}); return id; }, clearTimeout(id){timers.delete(id);},
  fetch(url) { return new Promise((resolve,reject)=>requests.push({url,resolve,reject})); } };
vm.runInNewContext(code, context);
async function flush() { for(let i=0;i<20;i++) await Promise.resolve(); }
async function advance(ms) { now+=ms; for(const [id,t] of [...timers]) if(t.time<=now){timers.delete(id);t.fn();} await flush(); }
function result(km) { return {ok:true,json:async()=>({routes:[{distance:km*1000,duration:1000,geometry:{type:'LineString',coordinates:[[60,56],[61,57]]}}]})}; }
(async()=>{
  window.g10SetStart(56,60); await flush(); assert.equal(requests.length,0);
  handlers.click({latlng:{lat:57,lng:61}}); await flush(); assert.equal(requests.length,1);
  handlers.click({latlng:{lat:58,lng:62}}); await flush();
  for(let i=0;i<15;i++)window.g10SetStart(56.001+i*.001,60);
  await flush(); assert.equal(requests.length,1,'GPS must not duplicate a pending request');
  await advance(1100); assert.equal(requests.length,2,'new destination is throttled');
  requests[1].resolve(result(20)); await flush();
  requests[0].resolve(result(99)); await flush();
  assert.equal(ready.length,1,'stale router reply cannot replace new route');
  assert.deepEqual(ready[0].slice(0,3),[20,58,62]);
  await advance(1100); elements.rebuild.onclick(); await flush();
  requests[2].reject(new Error('offline')); await flush(); assert.equal(errors.length,1);
  for(let i=0;i<15;i++)window.g10SetStart(56.1+i*.001,60);
  await advance(1500); assert.equal(requests.length,3,'offline GPS must not trigger retry storm');
  assert(requests.every(r=>r.url.includes('routed-bike')),'no silent car routing');
  assert(html.includes('sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY='));
  console.log('Route map regression checks: OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
