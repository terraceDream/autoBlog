import test from 'node:test';
import assert from 'node:assert/strict';
import {pageAction} from '../backend/src/main/resources/chrome-extension/page-actions.js';

test('table verification rejects flattened text and keeps cell borders',()=>{
 const previous={document:globalThis.document,location:globalThis.location,window:globalThis.window,DOMParser:globalThis.DOMParser};
 const cell=()=>({tagName:'TH',textContent:'항목',style:{}});
 const table=()=>({rows:[{cells:[cell()]}],style:{},setAttribute(){},querySelectorAll(){return this.rows[0].cells;}});
 const expected=table(),actual=table();let actualTables=[];
 const body={isContentEditable:true,textContent:'항목',querySelectorAll:s=>s==='table'?actualTables:[]};
 globalThis.document={querySelector:()=>({value:'제목'})};globalThis.location={origin:'https://test.tistory.com'};
 globalThis.window={tinymce:{editors:[{getBody:()=>body,save(){}}]}};
 globalThis.DOMParser=class{parseFromString(){return {body:{textContent:'항목'},querySelectorAll:s=>s==='table'?[expected]:[]};}};
 try{
  const payload={blogUrl:'https://test.tistory.com',title:'제목',html:'fixture'};
  assert.match(pageAction('verify',payload).error,/표의 행·열/);
  actualTables=[actual];assert.equal(pageAction('verify',payload).ready,true);
  assert.equal(actual.rows[0].cells[0].style.border,'1px solid #aebdb7');
 }finally{Object.assign(globalThis,previous);}
});
