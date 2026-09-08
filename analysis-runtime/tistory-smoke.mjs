import {chromium} from 'playwright';
import {fillEditor,savePrivate} from './tistory.mjs';
import readline from 'node:readline/promises';
import fs from 'node:fs/promises';
const context=await chromium.launchPersistentContext('../.tools/tistory-profile',{channel:'msedge',headless:false});
try{await context.addCookies(JSON.parse(await fs.readFile('../.tools/tistory-auth.json','utf8')).cookies);}catch{}
const page=context.pages()[0]||await context.newPage();
const payload={blogUrl:'https://plzundrstnd.tistory.com',title:'[비공개 테스트] Issue Desk 작성 연결 확인 2026-09-08',html:'<h2>자동 작성 연결 확인</h2><p>Issue Desk에서 티스토리로 제목과 본문을 전달하고 비공개 저장을 확인하기 위한 테스트 글입니다.</p><ul><li>본문 서식 입력</li><li>비공개 상태 확인</li></ul><p>실제 블로그 콘텐츠가 아닌 연결 확인용 글이며 공개 발행하지 않습니다.</p>',category:'',tags:['연결테스트']};
const io=readline.createInterface({input:process.stdin,output:process.stdout});
try{
 await page.goto(payload.blogUrl+'/manage/newpost/',{waitUntil:'domcontentloaded'});
 if(new URL(page.url()).hostname==='www.tistory.com')await page.getByRole('link',{name:'카카오계정으로 로그인',exact:true}).click();
 console.log('WAITING_FOR_EDITOR');
 await page.getByPlaceholder('제목을 입력하세요',{exact:true}).waitFor({timeout:600000});
 await context.storageState({path:'../.tools/tistory-auth.json'});
 console.log('EDITOR_AUTH_SAVED');
 console.log(await page.locator('body').ariaSnapshot());
 page.setDefaultTimeout(15000);
 await fillEditor(page,payload);
 console.log('FILLED_EDITOR');
 await page.getByRole('button',{name:'완료',exact:true}).click();
 console.log(await page.locator('body').ariaSnapshot());
 await page.screenshot({path:'../.tools/tistory-save-dialog.png'});
 const answer=await io.question('Type save to save this test privately: ');
 if(answer.trim()==='save'){
  try{console.log('SAVED_PRIVATE_URL '+await savePrivate(page,payload,true));}
  catch(e){console.log('SAVE_NOT_VERIFIED '+e.message);console.log(await page.locator('body').ariaSnapshot());await page.screenshot({path:'../.tools/tistory-save-result.png'});}
 }
}catch(e){console.log('SMOKE_FAILED '+e.message);console.log(await page.locator('body').ariaSnapshot());}
finally{io.close();await context.close();}
