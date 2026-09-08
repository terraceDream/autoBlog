import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs/promises';

// UI selectors are intentionally conservative. If the editor changes, stop before publication.
export async function fillEditor(page, payload) {
  const title = page.getByPlaceholder('제목을 입력하세요', { exact: true });
  await title.waitFor({ timeout: 240000 }); // User completes Kakao login in the visible browser.
  if (new URL(page.url()).origin !== payload.blogUrl.replace(/\/$/, '')) throw Error('Wrong blog');
  if (await title.inputValue()) throw Error('Existing unsaved title; refusing to overwrite');
  const body = page.frameLocator('iframe').locator('body[contenteditable="true"]');
  if (await body.count() !== 1) throw Error('Unsupported editor');
  if ((await body.innerText()).trim()) throw Error('Existing unsaved body; refusing to overwrite');
  await title.fill(payload.title);
  // Paste HTML through the editor's supported clipboard path so its change handlers run.
  await body.click();
  await page.context().grantPermissions(['clipboard-read','clipboard-write'], {origin:new URL(page.url()).origin});
  await page.evaluate(async html => navigator.clipboard.write([new ClipboardItem({
    'text/html':new Blob([html],{type:'text/html'}),
    'text/plain':new Blob([new DOMParser().parseFromString(html,'text/html').body.innerText],{type:'text/plain'})
  })]),payload.html);
  await body.press(process.platform === 'darwin' ? 'Meta+V' : 'Control+V');
  const expected=await page.evaluate(html=>new DOMParser().parseFromString(html,'text/html').body.textContent.replace(/\s+/g,''),payload.html);
  const actual=(await body.textContent()).replace(/\s+/g,'');
  if (!actual || actual!==expected || (await title.inputValue())!==payload.title)throw Error('Editor verification failed');
  // Only use exact matches; never silently assign a different category.
  const category=page.getByRole('combobox',{name:'카테고리 선택',exact:true});
  if(payload.category&&await category.count()===1){await category.click();const item=page.getByText(payload.category,{exact:true});if(await item.count()===1)await item.click();else await page.keyboard.press('Escape');}
  const tags=page.getByPlaceholder('태그입력',{exact:true});
  if(await tags.count()===1)for(const tag of payload.tags){await tags.fill(tag);await tags.press('Enter');}
  return expected;
}

export async function savePrivate(page,payload,dialogOpen=false){
  if(!dialogOpen)await page.getByRole('button',{name:'완료',exact:true}).click();
  const privateOption=page.getByRole('radio',{name:'비공개',exact:true});
  await privateOption.check();
  if(!await privateOption.isChecked())throw Error('Private visibility not verified');
  const save=page.getByRole('button',{name:'비공개 저장',exact:true});
  await save.click();
  await page.waitForURL(url=>url.origin===payload.blogUrl.replace(/\/$/,'')&&url.pathname.startsWith('/manage/posts'),{timeout:30000});
  return verifyPrivate(page,payload);
}

export async function verifyPrivate(page,payload){
  // Management page navigation alone is insufficient evidence that the intended post is private.
  await page.getByRole('link',{name:payload.title,exact:true}).waitFor({state:'visible',timeout:30000});
  const row=page.locator('li').filter({has:page.getByText(payload.title,{exact:true})}).filter({hasText:'비공개'});
  await row.waitFor({state:'visible',timeout:10000});
  if(await row.count()!==1)throw Error('Saved post visibility could not be verified');
  const link=row.getByRole('link',{name:payload.title,exact:true});
  const href=await link.getAttribute('href');
  const url=new URL(href,page.url());if(url.origin!==new URL(payload.blogUrl).origin)throw Error('Unexpected result URL');
  return url.toString();
}

async function main(){
 let raw='';for await(const chunk of process.stdin)raw+=chunk;
 const payload=JSON.parse(raw);if(!/^https:\/\/[a-zA-Z0-9-]+\.tistory\.com\/?$/.test(payload.blogUrl))throw Error('Invalid blog URL');
 payload.blogUrl=payload.blogUrl.replace(/\/$/,'');
 const root=path.dirname(fileURLToPath(import.meta.url));
 const context=await chromium.launchPersistentContext(path.join(root,'../.tools/tistory-profile'),{channel:'msedge',headless:false});
 const authFile=path.join(root,'../.tools/tistory-auth.json');
 try{await context.addCookies(JSON.parse(await fs.readFile(authFile,'utf8')).cookies);}catch{}
 const page=context.pages()[0]||await context.newPage();page.setDefaultTimeout(10000);
 let attemptedSave=false;
 try{
  await page.goto(payload.blogUrl+'/manage/newpost/',{waitUntil:'domcontentloaded'});
  await page.getByPlaceholder('제목을 입력하세요',{exact:true}).waitFor({timeout:240000});
  await context.storageState({path:authFile});
  await fillEditor(page,payload);
  await context.storageState({path:authFile});
  // Once saving starts, any failure is ambiguous and must never trigger automatic retry.
  attemptedSave=true;
  const url=await savePrivate(page,payload);
  console.log(JSON.stringify({status:'SAVED_PRIVATE',message:'티스토리 글 목록에서 제목과 비공개 상태를 확인했습니다.',url}));
  await context.close();
 }catch(error){
  console.log(JSON.stringify({status:attemptedSave?'UNKNOWN':'EDITOR_READY',message:attemptedSave?'저장 여부를 확인하지 못했습니다. 열린 티스토리에서 확인하세요. 재전송하지 않습니다.':'로그인 또는 편집기 확인이 필요합니다. 열린 브라우저에서 내용을 확인하세요. 아직 비공개 저장을 확인하지 못했습니다.',url:payload.blogUrl+'/manage/posts/'}));
  // Let the user inspect and finish manually. Close only when they close the browser.
  await new Promise(resolve=>context.on('close',resolve));
 }
}
if(process.argv[1]===fileURLToPath(import.meta.url))main().catch(()=>{console.log(JSON.stringify({status:'UNKNOWN',message:'티스토리 브라우저 실행에 실패했습니다. Edge 설치와 열린 연결 창을 확인하세요.',url:''}));process.exitCode=1;});
