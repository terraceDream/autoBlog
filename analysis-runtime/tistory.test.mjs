import test from 'node:test';
import assert from 'node:assert/strict';
import {savePrivate} from './tistory.mjs';

test('never saves when private visibility is not verified',async()=>{
 const actions=[];
 const page={getByRole:(role,options)=>({click:async()=>actions.push(options.name),check:async()=>actions.push('check-private'),isChecked:async()=>false})};
 await assert.rejects(()=>savePrivate(page,{blogUrl:'https://test.tistory.com'}),/Private visibility/);
 assert.deepEqual(actions,['완료','check-private']);
});
test('navigation without matching private row is not reported as saved',async()=>{
 const locator={filter:()=>locator,count:async()=>0,waitFor:async()=>{}};
 const page={getByRole:()=>({click:async()=>{},check:async()=>{},isChecked:async()=>true,waitFor:async()=>{}}),waitForURL:async()=>{},getByText:()=>({}),locator:()=>locator};
 await assert.rejects(()=>savePrivate(page,{blogUrl:'https://test.tistory.com',title:'제목'}),/visibility could not be verified/);
});
