import { fileURLToPath } from 'node:url';

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
  const expectedImages=await page.evaluate(html=>Array.from(new DOMParser().parseFromString(html,'text/html').querySelectorAll('img')).map(img=>img.getAttribute('src')),payload.html);
  await verifyImages(body,expectedImages);
  // Only use exact matches; never silently assign a different category.
  const category=page.getByRole('combobox',{name:'카테고리 선택',exact:true});
  if(payload.category&&await category.count()===1){await category.click();const item=page.getByText(payload.category,{exact:true});if(await item.count()===1)await item.click();else await page.keyboard.press('Escape');}
  const tags=page.getByPlaceholder('태그입력',{exact:true});
  if(await tags.count()===1)for(const tag of payload.tags){await tags.fill(tag);await tags.press('Enter');}
  return expected;
}

export async function verifyImages(body,expectedImages){
  if(!expectedImages.length)return;
  const images=await body.locator('img').evaluateAll(async nodes=>Promise.all(nodes.map(async img=>{
    if(!img.complete)await new Promise(resolve=>{const done=()=>{clearTimeout(timer);resolve();};const timer=setTimeout(done,15000);img.addEventListener('load',done,{once:true});img.addEventListener('error',done,{once:true});});
    return {src:img.getAttribute('src'),loaded:img.complete&&img.naturalWidth>0};
  })));
  if(images.length!==expectedImages.length||images.some((img,i)=>img.src!==expectedImages[i]||!img.loaded))throw Error('Image verification failed; inspect editor before saving');
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

// Legacy CLI entry point intentionally does not launch a browser.
if(process.argv[1]===fileURLToPath(import.meta.url)){
 console.log(JSON.stringify({status:'EDITOR_READY',message:'기존 Chrome의 Issue Desk 확장 프로그램을 연결하고 앱에서 전송해 주세요. 새 브라우저는 실행하지 않습니다.',url:''}));
}
