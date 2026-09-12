import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

const source=(await fs.readFile(new URL('../backend/src/main/resources/chrome-extension/worker.js',import.meta.url),'utf8'))
 .replace("import {config} from './config.js';","const config={base:'http://127.0.0.1:8080/api/tistory-browser',token:'test'};")
 .replace("import {pageAction} from './page-actions.js';","const pageAction=()=>{};");

test('reuses the existing Chrome window across jobs and never closes tabs',async()=>{
 const previousChrome=globalThis.chrome,previousFetch=globalThis.fetch;
 const data={},tabs=[],actions=[],results=[];
 let failSave=false,existingFound=false;
 globalThis.chrome={
  storage:{local:{get:async key=>({[key]:data[key]}),set:async value=>Object.assign(data,value),remove:async key=>delete data[key]}},
  tabs:{query:async()=>[{id:10,windowId:7,url:'https://test.tistory.com/manage/posts/'}],create:async options=>{tabs.push(options);return {id:tabs.length};},update:async()=>{},get:async()=>({status:'complete'})},
  scripting:{executeScript:async ({args})=>{const [action]=args;actions.push(action);if(action==='save'&&failSave)throw Error('lost connection');return [{result:action==='existing'?{ready:true,found:existingFound,url:'https://test.tistory.com/1'}:action==='saved'?{ready:true,url:'https://test.tistory.com/1'}:{ready:true,ok:true}}];}},
  action:{setBadgeText:async()=>{},setTitle:async()=>{},onClicked:{addListener(){}}},
  alarms:{create:async()=>{},onAlarm:{addListener(){}}},runtime:{getManifest:()=>({version:'1.1.0'}),onInstalled:{addListener(){}},onStartup:{addListener(){}}}
 };
 globalThis.fetch=async(url,options)=>{if(url.endsWith('/result'))results.push(JSON.parse(options.body));return {ok:true,json:async()=>({})};};
 try{
  const worker=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
  const payload={blogUrl:'https://test.tistory.com',title:'글',html:'<p>내용</p>',tags:[],category:''};
  await worker.run({id:'one',payload});await worker.run({id:'two',payload});
  assert.equal(tabs.length,2);assert.ok(tabs.every(tab=>tab.windowId===7));
  assert.deepEqual(results.map(r=>r.status),['SAVED_PRIVATE','SAVED_PRIVATE']);
  assert.equal(actions.filter(a=>a==='save').length,2);
  // Recover an interrupted worker by reporting ambiguity, never replaying its browser actions.
  const actionCount=actions.length;
  data.inFlight={id:'interrupted',result:{status:'UNKNOWN',message:'중단',url:''}};
  await worker.poll();assert.equal(actions.length,actionCount);assert.equal(results.at(-1).status,'UNKNOWN');
  failSave=true;await worker.run({id:'three',payload});assert.equal(results.at(-1).status,'UNKNOWN');
  existingFound=true;const saves=actions.filter(a=>a==='save').length;
  await worker.run({id:'four',payload});assert.equal(results.at(-1).status,'SAVED_PRIVATE');
  assert.equal(actions.filter(a=>a==='save').length,saves);
 }finally{globalThis.chrome=previousChrome;globalThis.fetch=previousFetch;}
});
