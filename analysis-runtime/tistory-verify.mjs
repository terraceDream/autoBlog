import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import {verifyPrivate} from './tistory.mjs';
const context=await chromium.launchPersistentContext('../.tools/tistory-profile',{channel:'msedge',headless:false});
try{
 await context.addCookies(JSON.parse(await fs.readFile('../.tools/tistory-auth.json','utf8')).cookies);
 const page=context.pages()[0]||await context.newPage();
 await page.goto('https://plzundrstnd.tistory.com/manage/posts/',{waitUntil:'domcontentloaded'});
 const url=await verifyPrivate(page,{blogUrl:'https://plzundrstnd.tistory.com',title:'[비공개 테스트] Issue Desk 작성 연결 확인 2026-09-08'});
 console.log('LOGIN_REUSED_AND_PRIVATE_VERIFIED '+url);
 await page.goto(url,{waitUntil:'domcontentloaded'});
 await page.getByText('자동 작성 연결 확인',{exact:true}).waitFor({timeout:15000});
 await page.getByText('본문 서식 입력',{exact:true}).waitFor({timeout:15000});
 console.log('SAVED_CONTENT_VERIFIED');
 await context.storageState({path:'../.tools/tistory-auth.json'});
}finally{await context.close();}
