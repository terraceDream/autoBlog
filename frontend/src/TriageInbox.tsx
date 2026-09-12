import { useEffect, useState } from 'react';
import { api, date } from './api';
import './triage.css';

type Item = {
  id: string;
  title: string;
  url: string;
  sourceName: string;
  publishedAt?: string;
  collectedAt: string;
  triageTier: string;
  triageScore: number;
  drafted: boolean;
  originalStatus: string;
  originalMessage: string;
  triage?: {
    titleKo: string;
    summaryKo: string;
    reason: string;
    readerBenefit: string;
    angle: string;
    missing: string;
    category: string;
    confidence: 'LOW' | 'MEDIUM' | 'HIGH';
  };
};
type Inbox = {
  items: Item[];
  total: number;
  page: number;
  busy: boolean;
  counts: { tier: string; count: number }[];
  runs: {
    status: string;
    message: string;
    processed: number;
    requested: number;
    inputTokens: number;
    outputTokens: number;
  }[];
};
const tiers: Record<string, string> = {
  T1: '1티어 · 우선 검토',
  T2: '2티어 · 검토 후보',
  T3: '3티어 · 낮은 우선순위',
  PENDING: '분류 대기',
};
export function TriageInbox({ topicId, blocked }: { topicId: string; blocked: boolean }) {
  const [data, setData] = useState<Inbox | null>(null),
    [tier, setTier] = useState(''),
    [category, setCategory] = useState(''),
    [query, setQuery] = useState(''),
    [search, setSearch] = useState(''),
    [sort, setSort] = useState('priority'),
    [page, setPage] = useState(0),
    [limit, setLimit] = useState(30),
    [ids, setIds] = useState<string[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [notice, setNotice] = useState(''),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(query);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [query]);
  useEffect(() => {
    setIds([]);
  }, [topicId, tier, category, search, sort]);
  useEffect(() => {
    let live = true;
    const load = () =>
      api<Inbox>(
        `/topics/${topicId}/editorial/triage?` +
          new URLSearchParams({ tier, category, q: search, sort, page: String(page) }),
      )
        .then((v) => {
          if (live) setData(v);
        })
        .catch((e) => {
          if (live) setError(e.message);
        });
    void load();
    const timer = setInterval(() => void load(), 4000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [topicId, tier, category, search, sort, page, revision]);
  const running = busy || blocked || data?.busy;
  async function run(path: string, body: unknown) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await api(`/topics/${topicId}/editorial/${path}`, 'POST', body);
      setNotice(
        path === 'focus'
          ? '선택 자료의 원문을 확인하고 집중 분석을 시작합니다. 아래에서 결과와 글 작성을 이어가세요.'
          : '분류를 시작했습니다. 새로 고침해도 완료한 요약은 유지됩니다.',
      );
      if (path === 'focus') setIds([]);
      setRevision((v) => v + 1);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const pending = data?.counts.find((c) => c.tier === 'PENDING')?.count || 0;
  return (
    <section className="triage-inbox" aria-labelledby="triage-title">
      <div className="triage-heading">
        <div>
          <span className="eyebrow">수집 → 1차 분류 → 선택 집중 분석 → 글 작성</span>
          <h2 id="triage-title">어떤 소재에 시간을 쓸까요?</h2>
          <p>
            한글 요약과 독자 효용을 보고 고르세요. 등급은 제목·짧은 설명에 근거한 1차 판단이며, 원문 검증과
            게재 추천은 집중 분석에서 진행합니다.
          </p>
        </div>
      </div>
      <div className="triage-tiers">
        <button
          className={!tier ? 'chosen' : ''}
          onClick={() => {
            setTier('');
            setPage(0);
          }}
        >
          전체 <b>{data?.counts.reduce((n, c) => n + c.count, 0) || 0}</b>
        </button>
        {Object.entries(tiers).map(([key, label]) => (
          <button
            key={key}
            className={tier === key ? 'chosen' : ''}
            onClick={() => {
              setTier(key);
              setPage(0);
            }}
          >
            {label} <b>{data?.counts.find((c) => c.tier === key)?.count || 0}</b>
          </button>
        ))}
      </div>
      <div className="triage-budget">
        <div>
          <strong>가볍게 분류하고, 고른 자료만 깊게</strong>
          <p>
            수집 후 미분류 자료를 우선순위대로 최대 30건 자동 분류합니다. 15건씩 묶어 설명 최대 900자만
            사용하며, 완료된 분류는 다시 호출하지 않습니다. 남은 {pending}건은 나눠 처리할 수 있습니다.
          </p>
        </div>
        <label>
          이번 분류 한도
          <select value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
            <option value={15}>15건</option>
            <option value={30}>30건</option>
            <option value={60}>60건</option>
          </select>
        </label>
        <button
          className="button"
          disabled={!!running || !pending}
          onClick={() => void run('triage', { limit })}
        >
          대기 자료 한글 요약·분류
        </button>
      </div>
      {data?.runs[0] && (
        <p className="triage-progress" role="status">
          {data.busy ? '분류 중 · ' : ''}
          {data.runs[0].message}{' '}
          <span>
            실제 사용: 입력 {data.runs[0].inputTokens.toLocaleString()} / 출력{' '}
            {data.runs[0].outputTokens.toLocaleString()} 토큰
          </span>
        </p>
      )}
      {error && (
        <p className="analysis-error" role="alert">
          {error}
        </p>
      )}
      {notice && <p role="status">{notice}</p>}
      <div className="triage-filters">
        <input
          aria-label="수집 소재 검색"
          placeholder="한글 요약·제목·독자 효용 검색"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <select
          aria-label="소재 분류"
          value={category}
          onChange={(e) => {
            setCategory(e.target.value);
            setPage(0);
          }}
        >
          <option value="">모든 AI 분류</option>
          {['AI 뉴스', 'AI 도구', '업무 활용', '개발·자동화', '개념·해설', '기타'].map((c) => (
            <option key={c}>{c}</option>
          ))}
        </select>
        <select
          aria-label="소재 정렬"
          value={sort}
          onChange={(e) => {
            setSort(e.target.value);
            setPage(0);
          }}
        >
          <option value="priority">등급·우선순위순</option>
          <option value="newest">최근 수집순</option>
        </select>
      </div>
      <div className="triage-selection">
        <span>
          <b>{ids.length}/5건 선택</b> · 원문 확보 자료만 집중 분석합니다. 미확보 자료는 제외 사유를 남깁니다.
        </span>
        <button
          className="button primary"
          disabled={!!running || !ids.length}
          onClick={() => void run('focus', { ids })}
        >
          선택 자료 원문 집중 분석
        </button>
        {ids.length > 0 && (
          <button className="button" onClick={() => setIds([])}>
            선택 해제
          </button>
        )}
      </div>
      <div className="triage-cards">
        {data?.items.map((item) => (
          <article className={`triage-card ${item.triageTier.toLowerCase()}`} key={item.id}>
            <div className="triage-meta">
              <span className="badge">{tiers[item.triageTier]}</span>
              {item.triage && (
                <span>
                  검토 점수 {item.triageScore} · {item.triage.category}
                </span>
              )}
              {item.drafted && <span className="badge green">작성 진행·완료</span>}
              <label className="triage-pick">
                <input
                  type="checkbox"
                  aria-label={`${item.triage?.titleKo || item.title} 집중 분석 선택`}
                  checked={ids.includes(item.id)}
                  disabled={item.drafted || !item.triage || (!ids.includes(item.id) && ids.length >= 5)}
                  onChange={(e) =>
                    setIds((v) => (e.target.checked ? [...v, item.id] : v.filter((id) => id !== item.id)))
                  }
                />{' '}
                분석 후보 선택
              </label>
            </div>
            <h3>
              <a href={item.url} target="_blank" rel="noreferrer">
                {item.triage?.titleKo || item.title}
              </a>
            </h3>
            <small>
              {item.sourceName} · 게시/등록 {date(item.publishedAt)} · 수집 {date(item.collectedAt)}
            </small>
            {item.triage ? (
              <>
                <p className="triage-summary">{item.triage.summaryKo}</p>
                <dl>
                  <dt>이 등급인 이유</dt>
                  <dd>{item.triage.reason}</dd>
                  <dt>독자가 얻는 것</dt>
                  <dd>{item.triage.readerBenefit}</dd>
                  <dt>글로 풀어볼 질문</dt>
                  <dd>{item.triage.angle}</dd>
                </dl>
                <details>
                  <summary>원제·근거 한계 확인</summary>
                  <p>{item.title}</p>
                  <p>{item.triage.missing}</p>
                  <p>
                    1차 판단 확신:{' '}
                    {{ LOW: '낮음', MEDIUM: '보통', HIGH: '높음' }[item.triage.confidence] ||
                      item.triage.confidence}{' '}
                    · 원문 검증 완료를 뜻하지 않습니다.
                  </p>
                </details>
              </>
            ) : (
              <p className="triage-summary muted">
                한글 요약·내용 등급은 아직 대기 중입니다. 규칙 기반 대기 순서 {item.triageScore}점으로 분류
                순서를 정했으며, 인기나 내용 품질을 확정한 점수가 아닙니다.
              </p>
            )}
            <p className="triage-original">
              원문:{' '}
              {item.originalStatus === 'AVAILABLE'
                ? '확보 완료'
                : item.originalStatus === 'UNCHECKED'
                  ? '아직 확인하지 않음 · 집중 분석 시 확보'
                  : item.originalMessage || '확보 실패'}
            </p>
          </article>
        ))}
      </div>
      {data && !data.items.length && (
        <p className="triage-empty">
          이 조건에 맞는 자료가 없습니다. 다른 등급을 선택하거나 수집·1차 분류를 실행하세요.
        </p>
      )}
      <div className="triage-pages">
        <button className="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          이전
        </button>
        <span>
          {data?.total || 0}건 · {page + 1}페이지
        </span>
        <button
          className="button"
          disabled={(page + 1) * 20 >= (data?.total || 0)}
          onClick={() => setPage((p) => p + 1)}
        >
          다음
        </button>
      </div>
    </section>
  );
}
