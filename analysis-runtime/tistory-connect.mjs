import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root=path.dirname(fileURLToPath(import.meta.url));
const context=await chromium.launchPersistentContext(path.join(root,'../.tools/tistory-profile'),{channel:'msedge',headless:false});
const page=context.pages()[0]||await context.newPage();
await page.goto('https://plzundrstnd.tistory.com/manage/newpost/',{waitUntil:'domcontentloaded'});
console.log('LOGIN_WINDOW_OPEN');
try {
 await page.waitForURL(url=>url.origin==='https://plzundrstnd.tistory.com'&&url.pathname.startsWith('/manage/newpost'),{timeout:600000});
 await page.getByPlaceholder('제목을 입력하세요',{exact:true}).waitFor({timeout:60000});
 console.log('EDITOR_CONNECTED');
 await context.storageState({path:path.join(root,'../.tools/tistory-auth.json')});
 // Only record editor controls after authenticated navigation; never log login forms or cookies.
 console.log(await page.locator('body').ariaSnapshot());
 await context.close();
}catch(e){console.log('CONNECTION_NOT_CONFIRMED');await context.close();process.exitCode=1;}
