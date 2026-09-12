import test from 'node:test';
import assert from 'node:assert/strict';
import {pageAction} from '../backend/src/main/resources/chrome-extension/page-actions.js';

test('selects only category list options and verifies the selected label without the caret text',()=>{
 const previous={document:globalThis.document,location:globalThis.location,window:globalThis.window,DOMParser:globalThis.DOMParser};
 const label={textContent:'카테고리 없음'};
 let opened=false;
 const combo={textContent:'카테고리 없음 더보기',querySelector:()=>label,click(){opened=true;}};
 const option={textContent:'AI 개발과 자동화',getClientRects:()=>[{}],click(){label.textContent=this.textContent;}};
 let options=[option];
 globalThis.document={querySelector:s=>s.startsWith('#category-btn')?(s.includes('.mce-txt')?label:combo):null,querySelectorAll:s=>s==='#category-list [role="option"]'?options:[]};
 globalThis.location={origin:'https://test.tistory.com'};
 globalThis.window={};globalThis.DOMParser=class{parseFromString(){return {};}};
 try{
  const payload={blogUrl:location.origin,category:'AI 개발과 자동화',html:''};
  assert.equal(pageAction('categoryVerified',payload).ready,false);
  assert.equal(pageAction('category',payload).selectCategory,true);assert.equal(opened,true);
  assert.equal(pageAction('selectCategory',payload).ok,true);
  assert.equal(pageAction('categoryVerified',payload).ready,true);
  opened=false;assert.equal(pageAction('category',payload).ok,true);assert.equal(opened,false);
  options=[option,option];assert.match(pageAction('selectCategory',payload).error,/찾지 못/);
  options=[];assert.match(pageAction('selectCategory',payload).error,/찾지 못/);
 }finally{Object.assign(globalThis,previous);}
});
