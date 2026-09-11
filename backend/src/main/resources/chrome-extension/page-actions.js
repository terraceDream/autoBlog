// Executed only on the requested blog. No cookies or login data leave Chrome.
export function pageAction(action,payload) {
  if(location.origin!==payload.blogUrl)return {error:'로그인된 대상 블로그인지 확인해 주세요.'};
  const text=node=>(node?.textContent||'').replace(/\s+/g,' ').trim();
  const visible=node=>!!node?.getClientRects().length;
  const button=label=>{
    const nodes=[...document.querySelectorAll('button,[role="button"]')].filter(n=>visible(n)&&text(n)===label);
    if(nodes.length!==1)throw Error(label+' 버튼을 정확히 찾지 못했습니다.');
    return nodes[0];
  };
  const title=document.querySelector('[placeholder="제목을 입력하세요"]');
  const editors=window.tinymce?.editors;
  const editor=editors&&[...editors].filter(e=>e.getBody()?.isContentEditable)[0];
  const body=editor?.getBody();
  const normalize=value=>value.replace(/\s+/g,'');
  const expectedDoc=new DOMParser().parseFromString(payload.html,'text/html');
  const input=(node,value)=>{
    const proto=node instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto,'value').set.call(node,value);
    node.dispatchEvent(new Event('input',{bubbles:true}));node.dispatchEvent(new Event('change',{bubbles:true}));
  };
  try {
    if(action==='ready')return {ready:!!title&&!!body};
    if(action==='fill') {
      if(!title||!body)throw Error('지원되는 티스토리 편집기를 찾지 못했습니다.');
      if(title.value||text(body))throw Error('이미 작성 중인 내용이 있어 덮어쓰지 않았습니다.');
      input(title,payload.title);
      editor.setContent(payload.html);editor.fire('change');editor.save();
      const tags=document.querySelector('[placeholder="태그입력"]');
      if(payload.tags?.length&&!tags)throw Error('태그 입력란을 확인해 주세요.');
      if(tags)for(const tag of payload.tags||[]){input(tags,tag);tags.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));tags.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}
      return {ok:true};
    }
    if(action==='verify') {
      if(!body||title.value!==payload.title||normalize(body.textContent)!==normalize(expectedDoc.body.textContent))return {ready:false};
      const tables=root=>[...root.querySelectorAll('table')].map(table=>[...table.rows].map(row=>[...row.cells].map(cell=>({tag:cell.tagName,text:normalize(cell.textContent)}))));
      if(JSON.stringify(tables(body))!==JSON.stringify(tables(expectedDoc)))throw Error('표의 행·열이 편집기에서 유지되지 않았습니다. 저장 전에 확인해 주세요.');
      for(const table of body.querySelectorAll('table')){
        table.style.borderCollapse='collapse';table.style.width='100%';table.setAttribute('border','1');
        for(const cell of table.querySelectorAll('th,td')){
          cell.style.border='1px solid #aebdb7';cell.style.padding='12px';cell.style.textAlign='left';cell.style.verticalAlign='top';
          if(cell.tagName==='TH')cell.style.backgroundColor='#eaf2ee';
        }
      }
      editor.save();
      const expected=[...expectedDoc.querySelectorAll('img')];const images=[...body.querySelectorAll('img')];
      if(images.length!==expected.length)throw Error('이미지가 편집기에서 누락되었습니다. 저장 전에 확인해 주세요.');
      return {ready:images.length===expected.length&&images.every((img,i)=>img.getAttribute('src')===expected[i].getAttribute('src')&&img.complete&&img.naturalWidth>0)};
    }
    if(action==='category'){
      if(!payload.category)return {ok:true};
      const select=document.querySelector('[role="combobox"][aria-label="카테고리 선택"]');
      if(!select)throw Error('카테고리 선택을 직접 확인해 주세요.');
      if(text(select)===payload.category)return {ok:true};
      select.click();return {selectCategory:true};
    }
    if(action==='selectCategory'){
      const nodes=[...document.querySelectorAll('[role="option"],button,a')].filter(n=>visible(n)&&text(n)===payload.category);
      if(nodes.length!==1)throw Error('같은 이름의 카테고리를 찾지 못했습니다.');
      nodes[0].click();return {ok:true};
    }
    if(action==='done'){button('완료').click();return {ok:true};}
    const privateInput=[...document.querySelectorAll('input[type="radio"]')].find(n=>{
      const label=[...(n.labels||[])].map(text).join(' ');
      return label==='비공개'||n.getAttribute('aria-label')==='비공개';
    });
    if(action==='privateReady')return {ready:!!privateInput};
    if(action==='private'){if(!privateInput)throw Error('비공개 선택을 확인해 주세요.');privateInput.click();return {ok:privateInput.checked};}
    if(action==='save'){
      if(!privateInput?.checked)throw Error('비공개가 선택되지 않았습니다.');
      button('비공개 저장').click();return {ok:true};
    }
    if(action==='saved'){
      if(!location.pathname.startsWith('/manage/posts'))return {ready:false};
      const links=[...document.querySelectorAll('a')].filter(n=>text(n)===payload.title&&n.closest('li')?.textContent.includes('비공개'));
      if(links.length!==1)return {ready:false};
      const url=new URL(links[0].href,location.href);
      return {ready:url.origin===payload.blogUrl,url:url.toString()};
    }
    return {error:'알 수 없는 편집 작업입니다.'};
  }catch(e){return {error:e.message};}
}
