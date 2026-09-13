import { useEffect, useState } from 'react';
import { api, date } from './api';
import blogConfig from './blog-config.json';
import { NaverExport } from './NaverExport';

type DraftImage = { url: string; sourceUrl: string; credit: string; license: string; licenseUrl: string; caption: string; afterParagraph: number; rightsConfirmed: boolean };
type Content = { title: string; html: string; category: string; tags: string[]; checks: string[]; images?: DraftImage[] };
type Draft = {
  id: string;
  status: string;
  message: string;
  createdAt: string;
  direction: string;
  result?: Content;
  input?: { sources: { title: string; url: string; message?: string; coverage?: string; imageCandidates?: {url:string;caption:string;sourceUrl:string}[] }[] };
  previewHtml?: string;
  inputTokens?: number;
  outputTokens?: number;
  remoteUrl?: string;
};
const working = (d: Draft) => ['WRITING', 'SENDING'].includes(d.status);
const labels: Record<string, string> = {
  WRITING: '원문 확인·작성 중',
  READY: '초안 준비',
  SENDING: '티스토리 전송 중',
  SAVED_PRIVATE: '비공개 저장 확인',
  FAILED: '작성 실패',
  UNKNOWN: '저장 여부 확인 필요',
  EDITOR_READY: '브라우저 확인 필요',
};
export function DraftComposer({ analysisId, issueIndex, generationBlocked=false }: { analysisId: string; issueIndex: number; generationBlocked?: boolean }) {
  const [direction, setDirection] = useState(''),
    [drafts, setDrafts] = useState<Draft[]>([]),
    [selected, setSelected] = useState('');
  const [content, setContent] = useState<Content | null>(null),
    [blog, setBlog] = useState(() => localStorage.getItem('issuedesk-tistory') || blogConfig.defaultTistoryUrl);
  const [tagText,setTagText]=useState('');
  const [browserConnection,setBrowserConnection]=useState<{ready:boolean;extensionPath:string;updateRequired?:boolean}|null>(null);
  useEffect(()=>{
    let alive=true;
    const refresh=()=>api<{ready:boolean;extensionPath:string;updateRequired?:boolean}>('/tistory-browser/status').then(value=>{if(alive)setBrowserConnection(value);}).catch(()=>{if(alive)setBrowserConnection(null);});
    void refresh();const timer=setInterval(()=>void refresh(),10000);
    return()=>{alive=false;clearInterval(timer);};
  },[]);
  const editedContent=()=>({...content!,tags:tagText.split(',').map(x=>x.trim()).filter(Boolean)});
  const [error, setError] = useState(''),
    [progress, setProgress] = useState(''),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  const draft = drafts.find((d) => d.id === selected);
  function addImage(url = '', sourceUrl = '', caption = '') {
    if (!content || (content.images?.length || 0) >= 3) return;
    setContent({...content, images: [...(content.images || []), {url, sourceUrl, caption, credit:'', license:'', licenseUrl:'', afterParagraph:2, rightsConfirmed:false}]});
  }
  useEffect(() => {
    let alive = true;
    api<Draft[]>(`/drafts?analysisId=${analysisId}&issueIndex=${issueIndex}`)
      .then((items) => {
        if (alive) {
          setDrafts(items);
          setSelected((old) => old || items[0]?.id || '');
        }
      })
      .catch((e) => alive && setError(e.message));
    return () => {
      alive = false;
    };
  }, [analysisId, issueIndex, revision]);
  const active = drafts.some(working);
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setRevision((x) => x + 1), 2000);
    return () => clearInterval(id);
  }, [active]);
  // Refresh editor only when the selected stored result changes, never on polling alone.
  const stored = JSON.stringify(draft?.result || null);
  useEffect(() => {
    setContent(JSON.parse(stored));
    setTagText((JSON.parse(stored)?.tags||[]).join(', '));
  }, [selected, stored]);
  async function action(fn: () => Promise<void>) {
    setBusy(true);
    setError('');
    setProgress('요청 처리 중…');
    try {
      await fn();
      setRevision((x) => x + 1);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      setProgress('');
    }
  }
  return (
    <section className="settings-card draft-composer">
      <h3>이 소재로 블로그 글 작성</h3>
      {browserConnection?.updateRequired && <p className="hint">새 글 작성·재시도 업데이트: Chrome 확장 관리에서 Issue Desk의 새로고침 버튼을 한 번 눌러 주세요. 로그인은 유지됩니다.</p>}
      <p className="hint">Chrome 연결: {browserConnection?.ready?'기존 Chrome 세션 연결됨 · 로그인과 열린 탭을 그대로 사용합니다.':'확장 프로그램 연결 대기'}</p>
      {!browserConnection?.ready && <details><summary>기존 Chrome 연결하기 · 최초 한 번</summary>
        <p><a href="/api/tistory-browser/extension.zip">이 서버용 Chrome 확장 다운로드</a> 후 압축을 풀어 연결하세요. 연결 인증 정보가 포함되어 있으므로 본인 PC에서만 사용하세요.</p>
        <p>티스토리에 로그인한 Chrome에서 chrome://extensions를 열고 개발자 모드를 켠 뒤 ‘압축해제된 확장 프로그램을 로드합니다’를 선택하세요.</p>
        <p>선택할 폴더: <code>{browserConnection?.extensionPath || '서버 연결 확인 중'}</code></p>
        <p>Issue Desk 확장 아이콘을 눌러 ON을 확인하세요. 이후에는 같은 Chrome 로그인 세션에서 글을 작성하고 저장한 탭도 남겨 둡니다.</p>
      </details>}
      <p className="hint">설명형 블로그 양식 적용 · 담백한 합니다·입니다체, 독자의 궁금증으로 시작, 짧은 문단과 예시, 공개 라이선스 이미지 자동 배치와 실제 HTML 표를 기본으로 작성합니다. 관련 이미지가 없으면 미삽입 이유를 표시합니다.</p>
      <p className="hint">
        연결된 원문 최대 5건에서 추출한 본문 전체를 길이 제한 없이 전달합니다. 원문을 가져오지 못하면 발췌문으로 대체하지 않고 작성을 중단합니다. 동적 로딩·별도 페이지 내용은 누락될 수 있습니다. 작성 버튼을 누르면 구독 사용량이 소모됩니다.
      </p>
      <label>
        글 작성 디렉션
        <textarea
          rows={4}
          maxLength={2000}
          value={direction}
          onChange={(e) => setDirection(e.target.value)}
          placeholder="예: 처음 접하는 독자에게 옆에서 설명하듯 써주세요. 핵심 변화가 일상에 어떤 차이를 만드는지 예시로 풀고, 원문 화면이나 비교 도식이 필요한 위치도 제안해주세요."
        />
      </label>
      <button
        className="button primary"
        disabled={busy || active || generationBlocked}
        onClick={() =>
          void action(async () => {
            const result = await api<{ id: string }>('/drafts', 'POST', {
              analysisId,
              issueIndex,
              direction,
            });
            setSelected(result.id);
          })
        }
      >
        {generationBlocked ? '진행 중인 AI 작업 완료 후 초안 작성 가능' : '원문 참고해 초안 작성'}
      </button>
      {error && (
        <p className="analysis-error" role="alert">
          {error}
        </p>
      )}
      {drafts.length > 0 && (
        <label>
          작성 이력
          <select value={selected} onChange={(e) => setSelected(e.target.value)}>
            {drafts.map((d) => (
              <option key={d.id} value={d.id}>
                {date(d.createdAt)} · {labels[d.status] || d.status}
              </option>
            ))}
          </select>
        </label>
      )}
      {draft && (
        <>
          <p role="status">
            <strong>{labels[draft.status]}</strong> · {draft.message}
          </p>
          {draft.input?.sources.map((s, i) => (
            <p className="hint" key={i}>
              <a href={s.url} target="_blank" rel="noreferrer">
                {s.title}
              </a>{' '}
              — {s.message || '원문 확인 대기'}
            </p>
          ))}
          {draft.inputTokens != null && (
            <p className="hint">
              작성 입력 {draft.inputTokens.toLocaleString()} · 출력 {draft.outputTokens?.toLocaleString()}{' '}
              토큰
            </p>
          )}
          {content && (
            <>
              <NaverExport draftId={draft.id} revisionKey={stored} />
              <label>
                제목
                <input
                  value={content.title}
                  maxLength={160}
                  disabled={!['READY','EDITOR_READY'].includes(draft.status)}
                  onChange={(e) => setContent({ ...content, title: e.target.value })}
                />
              </label>
              <label>
                카테고리 · 선택 사항
                <input
                  value={content.category}
                  maxLength={80}
                  disabled={!['READY','EDITOR_READY'].includes(draft.status)}
                  onChange={(e) => setContent({ ...content, category: e.target.value })}
                />
              </label>
              <p className="hint">비워 두면 카테고리를 지정하지 않습니다. 입력할 때는 티스토리에 있는 카테고리 이름을 사용하세요.</p>
              <label>
                태그 · 쉼표로 구분
                <input
                  value={tagText}
                  disabled={!['READY','EDITOR_READY'].includes(draft.status)}
                  onChange={(e) => setTagText(e.target.value)}
                />
              </label>
              <label>
                본문 HTML
                <textarea
                  rows={15}
                  value={content.html}
                  maxLength={40000}
                  disabled={!['READY','EDITOR_READY'].includes(draft.status)}
                  onChange={(e) => setContent({ ...content, html: e.target.value })}
                />
              </label>
              <details>
                <summary>본문 미리보기</summary>
                <p className="hint">저장된 본문과 이미지를 표시합니다. 수정한 내용은 ‘초안 수정 저장’ 후 반영됩니다.</p>
                <iframe
                  title="블로그 초안 미리보기"
                  sandbox=""
                  srcDoc={`<style>body{max-width:760px;margin:24px auto;padding:0 20px;font:17px/1.85 sans-serif;color:#273833}h2{margin-top:36px}figure{margin:28px 0}img{max-width:100%;height:auto}figcaption{font-size:13px;color:#586a64}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccd8d2;padding:10px}</style>${draft.previewHtml || draft.result?.html || ''}`}
                  style={{ width: '100%', height: 450, border: '1px solid #dde5e3' }}
                />
              </details>
              <h4>공개 전 확인</h4>
              <ul>
                {content.checks.map((c, i) => (
                  <li key={i}>{c}</li>
                ))}
              </ul>
              <p className="hint">
                원문 출처 링크는 전송 시 본문 하단에 붙습니다. 자동 선택된 공개 라이선스 이미지는 아래에 등록되어 있습니다. 위치와 설명을 수정하거나 다른 이미지를 추가할 수 있습니다.
              </p>
              {['READY','EDITOR_READY'].includes(draft.status) && (
                <>
                  <h4>설명을 돕는 이미지 · 최대 3개</h4>
                  <p className="hint">원문에 있다는 것만으로 재사용 가능한 것은 아닙니다. 직접 만든 이미지나 블로그 재사용·외부 삽입이 허용된 HTTPS 이미지 주소를 등록하세요. 출처와 사용 조건은 캡션에 함께 표시됩니다. 외부 주소 방식이므로 원본 삭제·접근 제한에 영향을 받습니다.</p>
                  <p><a href={`https://commons.wikimedia.org/w/index.php?search=${encodeURIComponent(content.title)}&title=Special:MediaSearch&type=image`} target="_blank" rel="noreferrer">Wikimedia Commons에서 관련 이미지 찾기</a></p>
                  {draft.input?.sources.flatMap(s => s.imageCandidates || []).map((img,i) => (
                    <p key={i} className="hint"><a href={img.sourceUrl} target="_blank" rel="noreferrer">원문 이미지 후보 {i+1}: {img.caption || '설명 없음'}</a>{' '}<button className="button" disabled={(content.images?.length || 0)>=3} onClick={()=>addImage(img.url,img.sourceUrl,img.caption)}>입력란에 가져오기</button> · 사용 조건 미확인</p>
                  ))}
                  {(content.images || []).map((img,i) => (
                    <fieldset key={i}>
                      <legend>이미지 {i+1}</legend>
                      {([['url','이미지 HTTPS 주소'],['caption','이미지 설명 · 대체 텍스트'],['credit','제작자 / 권리자'],['sourceUrl','원본 출처 페이지'],['license','사용 조건 · 예: CC BY 4.0 / 직접 제작 / 서면 허락'],['licenseUrl','라이선스 또는 사용 허락 근거 페이지']] as const).map(([key,label])=>(
                        <label key={key}>{label}<input value={img[key]} maxLength={key==='caption'?500:key==='credit'||key==='license'?300:2048} onChange={e=>setContent({...content,images:content.images!.map((item,n)=>n===i?{...item,[key]:e.target.value}:item)})}/></label>
                      ))}
                      <label>삽입 위치 · 몇 번째 문단 뒤 (0은 맨 위)<input type="number" min={0} max={100} value={img.afterParagraph} onChange={e=>setContent({...content,images:content.images!.map((item,n)=>n===i?{...item,afterParagraph:Number(e.target.value)}:item)})}/></label>
                      <label><input type="checkbox" checked={img.rightsConfirmed} onChange={e=>setContent({...content,images:content.images!.map((item,n)=>n===i?{...item,rightsConfirmed:e.target.checked}:item)})}/>이 블로그에서의 재사용(수익화 포함 여부)과 외부 삽입 조건을 확인했습니다.</label>
                      <button className="button" onClick={()=>setContent({...content,images:content.images!.filter((_,n)=>n!==i)})}>이미지 제외</button>
                    </fieldset>
                  ))}
                  <button className="button" disabled={(content.images?.length || 0)>=3} onClick={()=>addImage()}>이미지 추가</button>
                  <button
                    className="button"
                    disabled={busy}
                    onClick={() =>
                      void action(async () => {
                        await api(`/drafts/${draft.id}`, 'PUT', editedContent());
                      })
                    }
                  >
                    초안 수정 저장
                  </button>
                  <label>
                    티스토리 블로그 주소
                    <input
                      value={blog}
                      onChange={(e) => setBlog(e.target.value)}
                      placeholder="https://my-blog.tistory.com"
                    />
                  </label>
                  <p className="hint">
                    로그인된 Chrome의 같은 프로필에서 작성 탭을 엽니다. 새 브라우저나 별도 프로필을 실행하지 않으며 저장 후 탭도 닫지 않습니다. 티스토리 로그인 자체가 만료된 경우에만 다시 로그인해야 합니다.
                  </p>
                  <button
                    className="button primary"
                    disabled={busy || active}
                    onClick={() =>
                      void action(async () => {
                        const blogUrl = blog.trim();
                        if (!/^https:\/\/[a-zA-Z0-9-]+\.tistory\.com\/?$/.test(blogUrl)) {
                          throw new Error('티스토리 블로그 주소를 https://블로그이름.tistory.com 형식으로 입력해 주세요.');
                        }
                        setProgress('Chrome 연결 확인 중…');
                        const connection = await api<{ready:boolean;extensionPath:string;updateRequired?:boolean}>('/tistory-browser/status');
                        setBrowserConnection(connection);
                        if (!connection.ready) throw new Error('Chrome 연결이 끊겼습니다. 로그인한 Chrome에서 Issue Desk 확장 아이콘을 누른 뒤 다시 시도해 주세요.');
                        setProgress('초안 수정 저장 중…');
                        await api(`/drafts/${draft.id}`, 'PUT', editedContent());
                        localStorage.setItem('issuedesk-tistory', blogUrl);
                        setBlog(blogUrl);
                        setProgress('Chrome에 비공개 작성 요청 중…');
                        const sent = await api<Draft>(`/drafts/${draft.id}/tistory`, 'POST', { blogUrl });
                        setDrafts(items => items.map(item => item.id === sent.id ? sent : item));
                      })
                    }
                  >
                    {busy ? progress : '수정 저장 후 티스토리 비공개 작성'}
                  </button>
                </>
              )}
            </>
          )}
          <div aria-live="polite" style={{position:'sticky',bottom:0,background:'#fff',padding:'12px',borderTop:'1px solid #dde5e3',zIndex:2}}>
            {error ? <p className="analysis-error" role="alert">{error}</p> :
              <p role="status">{busy ? progress : `${labels[draft.status] || draft.status} · ${draft.message}`}
                {draft.status === 'SENDING' && ' Chrome 확장이 요청을 가져오는 데 최대 30초 정도 걸릴 수 있습니다. 확장 아이콘을 누르면 바로 확인합니다.'}
              </p>}
          </div>
          {draft.remoteUrl && (
            <a className="button" href={draft.remoteUrl} target="_blank" rel="noreferrer">
              티스토리에서 확인
            </a>
          )}
          {draft.status === 'UNKNOWN' && <div>
            <p>티스토리 글 목록에서 이 제목의 글이 저장되지 않았는지 확인하세요. 확인 후 기존 초안을 그대로 다시 사용할 수 있습니다.</p>
            <button className="button" disabled={busy || active} onClick={() => void action(async () => {
              const updated = await api<Draft>(`/drafts/${draft.id}/retry`, 'POST', {confirmedNotSaved:true});
              setDrafts(items => items.map(item => item.id === updated.id ? updated : item));
            })}>저장되지 않았음을 확인했어요 · 기존 초안으로 재시도</button>
          </div>}
        </>
      )}
    </section>
  );
}
