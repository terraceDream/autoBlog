import { useEffect, useState } from 'react';
import { api, date } from './api';
import blogConfig from './blog-config.json';

type Content = { title: string; html: string; category: string; tags: string[]; checks: string[] };
type Draft = {
  id: string;
  status: string;
  message: string;
  createdAt: string;
  direction: string;
  result?: Content;
  input?: { sources: { title: string; url: string; message?: string; coverage?: string }[] };
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
export function DraftComposer({ analysisId, issueIndex }: { analysisId: string; issueIndex: number }) {
  const [direction, setDirection] = useState(''),
    [drafts, setDrafts] = useState<Draft[]>([]),
    [selected, setSelected] = useState('');
  const [content, setContent] = useState<Content | null>(null),
    [blog, setBlog] = useState(() => localStorage.getItem('issuedesk-tistory') || blogConfig.defaultTistoryUrl);
  const [tagText,setTagText]=useState('');
  const editedContent=()=>({...content!,tags:tagText.split(',').map(x=>x.trim()).filter(Boolean)});
  const [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  const draft = drafts.find((d) => d.id === selected);
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
    try {
      await fn();
      setRevision((x) => x + 1);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="settings-card draft-composer">
      <h3>이 소재로 블로그 글 작성</h3>
      <p className="hint">
        연결된 원문 최대 5건을 각 8,000자까지 참고합니다. 접근 실패·유튜브는 저장된 발췌문으로 작성하며 한계를
        표시합니다. 작성 버튼을 누르면 구독 사용량이 소모됩니다.
      </p>
      <label>
        글 작성 디렉션
        <textarea
          rows={4}
          maxLength={2000}
          value={direction}
          onChange={(e) => setDirection(e.target.value)}
          placeholder="예: 초보 개발자 대상, 핵심 변화와 적용 방법 중심. 과장 없이 설명하고 확인되지 않은 성능 수치는 제외."
        />
      </label>
      <button
        className="button primary"
        disabled={busy || active}
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
        원문 참고해 초안 작성
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
              <label>
                제목
                <input
                  value={content.title}
                  maxLength={160}
                  disabled={draft.status !== 'READY'}
                  onChange={(e) => setContent({ ...content, title: e.target.value })}
                />
              </label>
              <label>
                카테고리
                <input
                  value={content.category}
                  maxLength={80}
                  disabled={draft.status !== 'READY'}
                  onChange={(e) => setContent({ ...content, category: e.target.value })}
                />
              </label>
              <p className="hint">티스토리에 같은 이름의 카테고리가 없으면 직접 선택해 주세요.</p>
              <label>
                태그 · 쉼표로 구분
                <input
                  value={tagText}
                  disabled={draft.status !== 'READY'}
                  onChange={(e) => setTagText(e.target.value)}
                />
              </label>
              <label>
                본문 HTML
                <textarea
                  rows={15}
                  value={content.html}
                  maxLength={40000}
                  disabled={draft.status !== 'READY'}
                  onChange={(e) => setContent({ ...content, html: e.target.value })}
                />
              </label>
              <details>
                <summary>본문 미리보기</summary>
                <iframe
                  title="블로그 초안 미리보기"
                  sandbox=""
                  srcDoc={content.html}
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
                원문 출처 링크는 전송 시 본문 하단에 붙습니다. 원본 이미지는 자동 복제하지 않습니다.
              </p>
              {draft.status === 'READY' && (
                <>
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
                    Edge 창이 열립니다. 필요한 경우 직접 로그인하세요. 비공개 저장만 시도하며, 편집기 변경이나
                    추가 인증이 있으면 멈춥니다.
                  </p>
                  <button
                    className="button primary"
                    disabled={busy || active || !/^https:\/\/[a-zA-Z0-9-]+\.tistory\.com\/?$/.test(blog)}
                    onClick={() =>
                      void action(async () => {
                        await api(`/drafts/${draft.id}`, 'PUT', editedContent());
                        localStorage.setItem('issuedesk-tistory', blog);
                        await api(`/drafts/${draft.id}/tistory`, 'POST', { blogUrl: blog });
                      })
                    }
                  >
                    수정 저장 후 티스토리 비공개 작성
                  </button>
                </>
              )}
            </>
          )}
          {draft.remoteUrl && (
            <a className="button" href={draft.remoteUrl} target="_blank" rel="noreferrer">
              티스토리에서 확인
            </a>
          )}
        </>
      )}
    </section>
  );
}
