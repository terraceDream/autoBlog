import {config} from './config.js';
import {pageAction} from './page-actions.js';
let busy=false;
async function request(path,body={}){
  const response=await fetch(config.base+path,{method:'POST',headers:{'Content-Type':'application/json','X-IssueDesk-Token':config.token},body:JSON.stringify(body),signal:AbortSignal.timeout(10000)});
  if(!response.ok)throw Object.assign(Error('연결 오류'),{status:response.status});
  return response.json();
}
async function deliver(record){
  try{await request('/jobs/'+record.id+'/result',record.result);}
  catch(e){if(e.status!==409)throw e;}
  await chrome.storage.local.remove('inFlight');
}
export async function run(job){
  const p=job.payload;
  if(!/^https:\/\/[a-zA-Z0-9-]+\.tistory\.com\/?$/.test(p.blogUrl))throw Error('잘못된 블로그 주소');
  p.blogUrl=p.blogUrl.replace(/\/$/,'');
  // Persist before any browser action. Restarting the worker never replays a claimed job.
  await chrome.storage.local.set({inFlight:{id:job.id,result:{status:'UNKNOWN',message:'브라우저 연결이 중단되었습니다. 열린 탭의 저장 여부를 확인해 주세요. 자동 재전송하지 않습니다.',url:p.blogUrl+'/manage/posts/'}}});
  let attempted=false;
  let result;
  try{
    const tabs=await chrome.tabs.query({url:p.blogUrl+'/*'});
    // A new tab in the same ordinary Chrome profile shares its existing login cookies.
    // Existing editor tabs and saved posts are never navigated away from or closed.
    const options={url:p.blogUrl+'/manage/newpost/#issuedesk-new-draft',active:true};
    if(tabs[0]?.windowId!==undefined)options.windowId=tabs[0].windowId;
    const tab=await chrome.tabs.create(options);
    const action=async name=>{
      const state=await chrome.tabs.get(tab.id);
      if(state.status!=='complete')return {ready:false};
      const values=await chrome.scripting.executeScript({target:{tabId:tab.id},world:'MAIN',func:pageAction,args:[name,p]});
      const value=values[0]?.result;
      if(value?.error)throw Error(value.error);
      return value||{ready:false};
    };
    const wait=async(name,seconds)=>{
      const end=Date.now()+seconds*1000;
      while(Date.now()<end){const state=await action(name);if(state.ready)return state;await new Promise(r=>setTimeout(r,500));}
      throw Error('로그인 또는 편집기 상태를 열린 탭에서 확인해 주세요.');
    };
    await wait('ready',30);
    await action('fill');
    await wait('verify',20);
    const category=await action('category');
    if(category.selectCategory){await new Promise(r=>setTimeout(r,300));await action('selectCategory');}
    await action('done');await wait('privateReady',10);
    const privateState=await action('private');if(!privateState.ok)throw Error('비공개 선택 확인 실패');
    attempted=true;await action('save');
    const saved=await wait('saved',40);
    result={status:'SAVED_PRIVATE',message:'기존 Chrome 세션에서 비공개 저장을 확인했습니다. 브라우저와 탭을 열어 두었습니다.',url:saved.url};
  }catch(e){result={status:attempted?'UNKNOWN':'EDITOR_READY',message:(attempted?'저장 여부를 확인해 주세요. 자동 재전송하지 않습니다. ':'아직 저장하지 않았습니다. ')+String(e.message).slice(0,400),url:p.blogUrl+'/manage/posts/'};}
  const record={id:job.id,result};await chrome.storage.local.set({inFlight:record});await deliver(record);
}
export async function poll(){
  if(busy)return;busy=true;
  try{
    const {inFlight}=await chrome.storage.local.get('inFlight');
    if(inFlight)await deliver(inFlight);
    const job=await request('/claim',{version:chrome.runtime.getManifest().version});
    await chrome.action.setBadgeText({text:'ON'});
    await chrome.action.setTitle({title:'Issue Desk · 기존 Chrome 세션 연결됨'});
    if(job.id)await run(job);
  }catch{
    await chrome.action.setBadgeText({text:'!'});
    await chrome.action.setTitle({title:'Issue Desk 서버를 실행하고 다시 눌러 주세요'});
  }finally{busy=false;}
}
async function start(){await chrome.alarms.create('issuedesk',{periodInMinutes:0.5});await poll();}
chrome.runtime.onInstalled.addListener(start);
chrome.runtime.onStartup.addListener(start);
chrome.action.onClicked.addListener(poll);
chrome.alarms.onAlarm.addListener(alarm=>{if(alarm.name==='issuedesk')void poll();});
