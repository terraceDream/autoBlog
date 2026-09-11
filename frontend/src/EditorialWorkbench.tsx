import { useEffect, useRef, useState } from 'react';
import { api, date } from './api';
import { DraftComposer } from './DraftComposer';
import './editorial.css';

type Editorial = {
  decision: string;
  priority: number;
  reason: string;
  readerQuestion: string;
  readerBenefit: string;
  evidence: string;
  openingScene: string;
};
type Issue = {
  sourceIds: string[];
  title: string;
  summary: string;
  audience: string;
  angle: string;
  suggestedTitles: string[];
  outline: string[];
  uncertainties: string[];
  editorial?: Editorial;
};
type Candidate = {
  signals?: { discussionUrl?: string; observedAt?: string };
  id: string;
  title: string;
  url: string;
  score: number;
  reasons: string[];
  sourceName: string;
};
type Run = {
  id: string;
  status: string;
  message: string;
  createdAt: string;
  selection?: { eligible: number; selected: Candidate[]; note: string };
  analysis?: {
    id: string;
    status: string;
    message: string;
    result?: { issues: Issue[]; excluded: { sourceId: string; reason: string }[] };
  };
};
type Board = {
  aiBusy?: boolean;
  hidden?: string[];
  settings: { audience: string; automatic: boolean };
  collecting: boolean;
  runs: Run[];
  candidates: Candidate[];
  modules: { type: string; name: string; ready: boolean }[];
  sources: { name: string; type: string; enabled: boolean }[];
  collections?: {
    status: string;
    sources: { sourceName: string; status: string; added: number; message: string }[];
  }[];
};
const states: Record<string, string> = {
  SCREENING: '원문·후보 검토 중',
  ANALYZING: '추천 기획 중',
  SUCCESS: '추천 준비',
  EMPTY: '새 후보 없음',
  FAILED: '확인 필요',
  AUTH_REQUIRED: 'AI 로그인 필요',
  LIMIT_REACHED: '사용량 한도',
  CANCELLED: '중단됨',
};
export function EditorialWorkbench({ topicId, onSources }: { topicId: string; onSources: () => void }) {
  const [board, setBoard] = useState<Board | null>(null),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [audience, setAudience] = useState(''),
    [selected, setSelected] = useState<{ job: string; index: number; issue: Issue } | null>(null),
    [notice, setNotice] = useState('');
  const selectedPanel = useRef<HTMLElement>(null);
  useEffect(() => {
    selectedPanel.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [selected]);
  useEffect(() => {
    let live = true;
    let initialized = false;
    const refresh = () =>
      api<Board>(`/topics/${topicId}/editorial`)
        .then((b) => {
          if (live) {
            setBoard(b);
            if (!initialized) {
              setAudience(b.settings.audience);
              initialized = true;
            }
          }
        })
        .catch((e) => {
          if (live) setError(e.message);
        });
    void refresh();
    const timer = setInterval(() => void refresh(), 4000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [topicId]);
  async function action(path: string, method = 'POST', body?: unknown) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await api(`/topics/${topicId}/editorial${path}`, method, body);
      setBoard(await api<Board>(`/topics/${topicId}/editorial`));
      setNotice(
        path === '/settings'
          ? '설정을 저장했습니다.'
          : '작업을 시작했습니다. 이 화면에서 진행 상황과 결과를 확인할 수 있습니다.',
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const latest = board?.runs[0];
  const active = !!board?.aiBusy || !!board?.collecting || (!!latest && ['SCREENING', 'ANALYZING'].includes(latest.status));
  const readyRun = board?.runs.find((r) => r.analysis?.result);
  const job = readyRun?.analysis;
  const cards = (job?.result?.issues || [])
    .map((issue, index) => ({ issue, index }))
    .filter((c) => !c.issue.sourceIds?.every((id) => board?.hidden?.includes(id)))
    .sort((a, b) => (b.issue.editorial?.priority || 0) - (a.issue.editorial?.priority || 0));
  const recommended = cards.filter((c) => c.issue.editorial?.decision === 'RECOMMEND');
  function card({ issue, index }: { issue: Issue; index: number }) {
    const e = issue.editorial;
    return (
      <article className="editorial-card" key={index}>
        <div className="editorial-card-meta">
          <span className={`badge ${e?.decision === 'RECOMMEND' ? 'green' : 'gray'}`}>
            {e?.decision === 'RECOMMEND'
              ? '작성 추천'
              : e?.decision === 'SKIP'
                ? '이번에는 제외'
                : '추가 확인'}
          </span>
          <span>편집 적합도 {e?.priority ?? '미평가'}</span>
        </div>
        <h3>{issue.suggestedTitles?.[0] || issue.title}</h3>
        <p>{e?.readerQuestion || issue.angle}</p>
      <dl>
          <dt>읽고 얻는 것</dt>
          <dd>{e?.readerBenefit || issue.summary}</dd>
          <dt>추천 판단</dt>
          <dd>{e?.reason || '이전 분석입니다. 새 추천을 실행하면 판단 근거가 추가됩니다.'}</dd>
          <dt>근거 상태</dt>
          <dd>{e?.evidence || issue.uncertainties.join(' ')}</dd>
      </dl>
      <details><summary>근거 원문 확인</summary><ul>{readyRun?.selection?.selected.filter(s=>issue.sourceIds.includes(s.id)).map(s=><li key={s.id}><a href={s.url} target="_blank" rel="noreferrer">{s.title}</a></li>)}</ul></details>
        <button className="button primary" onClick={() => setSelected({ job: job!.id, index, issue })}>
          기획 확인 · 글 작성
        </button>
        <button
          className="button"
          disabled={busy}
          onClick={() =>
            void (async () => {
              setBusy(true);
              setError('');
              try {
                await api('/articles/status', 'PATCH', { topicId, ids: issue.sourceIds, status: 'HIDDEN' });
                setBoard(await api<Board>(`/topics/${topicId}/editorial`));
                setNotice(
                  '이 소재를 다음 추천에서 제외했습니다. 수집 결과의 숨김 필터에서 복원할 수 있습니다.',
                );
              } catch (e) {
                setError((e as Error).message);
              } finally {
                setBusy(false);
              }
            })()
          }
        >
          관심 없는 소재 제외
        </button>
      </article>
    );
  }
  const chosen = selected?.issue;
  return (
    <div className="editorial-workbench">
      <section className="editorial-hero">
        <div>
          <span className="eyebrow">오늘의 편집 작업실</span>
          <h2>
            목록을 훑는 대신,
            <br />쓸 만한 이야기부터.
          </h2>
          <p>분야에 맞는 원문을 찾고, 독자에게 도움이 되는 소재를 추립니다.</p>
        </div>
        <div className="editorial-actions">
          <button
            className="button primary"
            disabled={busy || active}
            onClick={() => void action('/collect')}
          >
            {active ? '자료를 검토하고 있습니다…' : '원문 발견부터 추천까지'}
          </button>
          <button
            className="button"
            disabled={busy || active || !board?.candidates.length}
            onClick={() => void action('/recommend')}
          >
            수집된 자료만 다시 선별
          </button>
          <small>추천에는 AI 구독 사용량이 소모됩니다. 자동으로 글을 게시하지 않습니다.</small>
        </div>
      </section>
      {error && (
        <p role="alert" className="analysis-error">
          {error}
        </p>
      )}
      {notice && <p role="status">{notice}</p>}
      <section className="editorial-setup">
        <label>
          이 블로그의 독자
          <input
            value={audience}
            maxLength={300}
            onChange={(e) => setAudience(e.target.value)}
            placeholder="예: AI를 업무에 활용하려는 실무자"
          />
        </label>
        <button
          className="button"
          disabled={busy || !audience.trim()}
          onClick={() =>
            void action('/settings', 'PUT', { audience, automatic: board?.settings.automatic ?? false })
          }
        >
          독자 설정 저장
        </button>
        <p>독자 설정은 추천 이유와 글의 설명 수준에 반영됩니다.</p>
      </section>
      <div className="editorial-flow">
        <span>① 키워드로 원문 발견</span>
        <span>② 후보 최대 10건 기획 검토</span>
        <span>③ 추천 소재 선택 후 작성</span>
      </div>
      {board?.collections?.[0] && (
        <details className="editorial-details">
          <summary>
            최근 수집: 신규 {board.collections[0].sources.reduce((n, s) => n + s.added, 0)}건 · 실패{' '}
            {board.collections[0].sources.filter((s) => s.status === 'FAILED').length}개 출처
          </summary>
          <ul>
            {board.collections[0].sources.map((s, i) => (
              <li key={i}>
                {s.sourceName} ·{' '}
                {s.status === 'SUCCESS' ? `신규 ${s.added}건` : s.status === 'FAILED' ? '실패' : '수집 중'} ·{' '}
                {s.message}
              </li>
            ))}
          </ul>
        </details>
      )}
      {latest && (
        <p className="editorial-progress" role="status">
          <strong>{board?.collecting ? '수집 중' : states[latest.status] || latest.status}</strong> ·{' '}
          {latest.analysis?.message || latest.message}
          {latest.selection && <small>{latest.selection.note}</small>}
        </p>
      )}
      {!latest && (
        <p className="hint">처음에는 위 버튼으로 시작하세요. 사이트 주소를 하나씩 등록할 필요가 없습니다.</p>
      )}
      {readyRun && (
        <div className="section-heading">
          <div>
            <h2>먼저 검토할 소재 {recommended.length}개</h2>
            <p>{date(readyRun.createdAt)} 기준 · 편집 적합도는 예상 조회수나 클릭률이 아닙니다.</p>
          </div>
        </div>
      )}
      <div className="editorial-grid">{recommended.map(card)}</div>
      {job?.result && recommended.length === 0 && (
        <p>현재 근거로 자신 있게 추천할 소재가 없습니다. 아래 보류 사유를 확인하거나 새 자료를 수집하세요.</p>
      )}
      {cards.some((c) => c.issue.editorial?.decision !== 'RECOMMEND') && (
        <details className="editorial-details">
          <summary>보류·제외 소재와 판단 이유</summary>
          <div className="editorial-grid">
            {cards.filter((c) => c.issue.editorial?.decision !== 'RECOMMEND').map(card)}
          </div>
        </details>
      )}
      {!!job?.result?.excluded.length && (
        <details className="editorial-details">
          <summary>분석에서 제외한 자료 {job.result.excluded.length}건</summary>
          <ul>
            {job.result.excluded.map((e, i) => (
              <li key={i}>{e.reason}</li>
            ))}
          </ul>
        </details>
      )}
      {chosen && selected && (
        <section ref={selectedPanel} className="editorial-selected">
          <button className="button" onClick={() => setSelected(null)}>
            기획 닫기
          </button>
          <h2>{chosen.title}</h2>
          <p>
            <strong>독자:</strong> {chosen.audience}
          </p>
          <p>
            <strong>글의 의도:</strong> {chosen.angle}
          </p>
          <p>
            <strong>도입에서 다룰 상황:</strong> {chosen.editorial?.openingScene}
          </p>
          <ol>
            {chosen.outline.map((s, i) => (
              <li key={i}>{s}</li>
            ))}
          </ol>
          <DraftComposer
            generationBlocked={active}
            key={`${selected.job}-${selected.index}`}
            analysisId={selected.job}
            issueIndex={selected.index}
          />
        </section>
      )}
      <details className="editorial-details">
        <summary>후보 선정 근거 · {board?.candidates.length || 0}건</summary>
        <p>
          최근 자료 최대 500건 중 최근 30일, 분야 키워드, 숨김 및 작성 이력을 확인합니다. 도메인당 최대 3건을
          골라 한 출처에 치우치는 것을 줄입니다. 관심 신호가 없다는 것은 인기가 없다는 뜻이 아닙니다.
        </p>
        {board?.candidates.slice(0, 30).map((c) => (
          <div className="editorial-candidate" key={c.id}>
            <a href={c.url} target="_blank" rel="noreferrer">
              {c.title}
            </a>
            <span>
              후보 점수 {c.score} · {c.reasons.join(' / ')}
            </span>
            {c.signals?.discussionUrl && <a href={c.signals.discussionUrl} target="_blank" rel="noreferrer">관심 신호 원문 · {date(c.signals.observedAt)}</a>}
          </div>
        ))}
      </details>
      <details className="editorial-details">
        <summary>자동 수집 설정과 연결 상태</summary>
        <p>
          기술 커뮤니티에서 키워드로 외부 원문을 발견합니다. YouTube·네이버 검색은 서버에 API 키가 있을 때
          자동 연결됩니다. YouTube는 채널 지정 없이 검색할 수 있지만 영상 내용·자막을 읽는 기능은 아직
          없습니다.
        </p>
        <ul>
          {board?.modules.map((m) => (
            <li key={m.type}>
              {m.name} · {m.ready ? '연결 가능' : 'API 설정 필요'}
            </li>
          ))}
        </ul>
        <p>
          활성 출처 {board?.sources.filter((s) => s.enabled).length || 0}개 ·{' '}
          <button className="button" onClick={onSources}>
            출처 관리
          </button>
        </p>
        <label>
          <input
            type="checkbox"
            checked={board?.settings.automatic ?? false}
            disabled={busy || active}
            onChange={(e) => void action('/settings', 'PUT', { audience, automatic: e.target.checked })}
          />{' '}
          수집할 때 검색 출처 자동 연결 · 수집 후 AI 추천 실행
        </label>
        <p>
          예약 수집에도 적용됩니다. 예약 시간은 분야 설정에서 변경할 수 있고, 서버가 켜져 있어야 실행됩니다.
        </p>
      </details>
    </div>
  );
}
