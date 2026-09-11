import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('../backend/src/main/resources/chrome-extension/new-draft.js',import.meta.url),'utf8');
test('only automated new tabs decline draft restoration and preserve other confirmations',()=>{
  for(const hash of ['', '#issuedesk-new-draft']) {
    const calls=[];const window={confirm:message=>{calls.push(message);return true;}};
    vm.runInNewContext(source,{window,location:{hash}});
    assert.equal(window.confirm('작성 중인 글을 이어쓰겠습니까?'),hash==='');
    assert.equal(window.confirm('저장된 글을 불러오시겠습니까?'),hash==='');
    assert.equal(window.confirm('삭제하시겠습니까?'),true);
    assert.equal(calls.length,hash===''?3:1);
  }
});
