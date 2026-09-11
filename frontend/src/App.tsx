import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import {
  Plus,
  Search,
  Rss,
  LayoutGrid,
  Settings2,
  History,
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
  X,
  Check,
  RefreshCw,
  FolderOpen,
  Bookmark,
  Eye,
  EyeOff,
  Radio,
  Trash2,
  Pencil,
  ChevronDown,
  CircleAlert,
  Layers3,
  SlidersHorizontal,
  Sparkles,
} from 'lucide-react';
import { AnalysisDialog, AnalysisWorkspace } from './Analysis';
import { EditorialWorkbench } from './EditorialWorkbench';
import { api, date, mediaNames, statusNames } from './api';
import type { Topic, TopicInput, Source, SourceInput, Article, Module, Run, Page, Stats } from './api';

type Tab = 'editorial' | 'results' | 'analysis' | 'sources' | 'settings' | 'history';
const tabs: { id: Tab; name: string; icon: typeof Search }[] = [
  { id: 'editorial', name: '추천 작업실', icon: Sparkles },
  { id: 'results', name: '수집 결과', icon: LayoutGrid },
  { id: 'analysis', name: '분석된 소재', icon: Sparkles },
  { id: 'sources', name: '수집 출처', icon: Rss },
  { id: 'settings', name: '분야 설정', icon: Settings2 },
  { id: 'history', name: '실행 이력', icon: History },
];
const emptyTopic: TopicInput = {
  name: '',
  description: '',
  instructions: '',
  keywords: [],
  exclusions: [],
  tags: [],
  language: 'ko',
  region: 'KR',
  active: true,
  scheduleEnabled: false,
  cron: '0 0 9 * * *',
  timezone: 'Asia/Seoul',
};

function Modal({
  title,
  children,
  onClose,
  wide = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current!;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className={wide ? 'modal wide' : 'modal'}
      onCancel={onClose}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="modal-head">
        <h2>{title}</h2>
        <button className="icon-button" aria-label="닫기" onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  );
}
function TagInput({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string[];
  onChange: (v: string[]) => void;
  placeholder: string;
}) {
  const [draft, setDraft] = useState('');
  function commit() {
    const next = draft
      .split(/[,\n]/)
      .map((s) => s.trim())
      .filter(Boolean);
    if (next.length) onChange([...new Set([...value, ...next])].slice(0, 30));
    setDraft('');
  }
  return (
    <label className="field">
      {label}
      <div className="tag-input">
        {value.map((v) => (
          <span className="chip" key={v}>
            {v}
            <button
              type="button"
              aria-label={`${v} 삭제`}
              onClick={() => onChange(value.filter((x) => x !== v))}
            >
              <X size={12} />
            </button>
          </span>
        ))}
        <input
          value={draft}
          maxLength={100}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={value.length ? '추가…' : placeholder}
          onBlur={commit}
          onKeyDown={(e) => {
            if ((e.key === 'Enter' && !e.nativeEvent.isComposing) || e.key === ',') {
              e.preventDefault();
              commit();
            }
          }}
        />
      </div>
      <small>Enter 또는 쉼표로 추가</small>
    </label>
  );
}
function TopicForm({
  initial,
  onSave,
  onCancel,
  busy,
}: {
  initial: TopicInput;
  onSave: (p: TopicInput) => Promise<void>;
  onCancel: () => void;
  busy: boolean;
}) {
  const [form, setForm] = useState<TopicInput>(initial);
  const set = <K extends keyof TopicInput>(k: K, v: TopicInput[K]) => setForm((f) => ({ ...f, [k]: v }));
  function submit(e: FormEvent) {
    e.preventDefault();
    void onSave(form);
  }
  return (
    <form onSubmit={submit} className="form-body">
      <label className="field">
        분야명{' '}
        <input
          autoFocus
          required
          maxLength={120}
          placeholder="예: AI 개발 도구"
          value={form.name}
          onChange={(e) => set('name', e.target.value)}
        />
      </label>
      <label className="field">
        설명
        <textarea
          rows={2}
          maxLength={4000}
          placeholder="어떤 소식을 모으는 분야인가요?"
          value={form.description}
          onChange={(e) => set('description', e.target.value)}
        />
      </label>
      <label className="field">
        상세 수집 지침
        <textarea
          rows={3}
          maxLength={10000}
          placeholder="예: 신규 출시와 주요 업데이트, 실제 사용 후기 중심"
          value={form.instructions}
          onChange={(e) => set('instructions', e.target.value)}
        />
        <small>현재 수집은 아래 키워드와 출처 조건으로 실행됩니다. 상세 지침은 관리용으로 저장됩니다.</small>
      </label>
      <div className="form-grid">
        <TagInput
          label="포함 키워드"
          value={form.keywords}
          onChange={(v) => set('keywords', v)}
          placeholder="AI 코딩, 개발 도구"
        />
        <TagInput
          label="제외 키워드"
          value={form.exclusions}
          onChange={(v) => set('exclusions', v)}
          placeholder="광고, 유료 강의"
        />
      </div>
      <TagInput
        label="관리 태그"
        value={form.tags}
        onChange={(v) => set('tags', v)}
        placeholder="업무, 기술"
      />
      <div className="form-grid">
        <label className="field">
          언어
          <select value={form.language} onChange={(e) => set('language', e.target.value)}>
            <option value="ko">한국어</option>
            <option value="en">영어</option>
            <option value="ja">일본어</option>
          </select>
        </label>
        <label className="field">
          지역
          <select value={form.region} onChange={(e) => set('region', e.target.value)}>
            <option value="KR">한국</option>
            <option value="US">미국</option>
            <option value="JP">일본</option>
          </select>
        </label>
      </div>
      <small>언어·지역은 YouTube 검색에 적용됩니다. RSS와 네이버 검색은 제공된 자료를 사용합니다.</small>
      <label className="check-line">
        <input type="checkbox" checked={form.active} onChange={(e) => set('active', e.target.checked)} /> 이
        분야 활성화
      </label>
      <div className="schedule-box">
        <label className="check-line">
          <input
            type="checkbox"
            checked={form.scheduleEnabled}
            onChange={(e) => set('scheduleEnabled', e.target.checked)}
          />{' '}
          정기 수집 사용
        </label>
        {form.scheduleEnabled && (
          <>
            <div className="form-grid">
              <label className="field">
                수집 주기
                <select
                  value={
                    ['0 0 9 * * *', '0 0 9 * * MON-FRI', '0 0 */6 * * *'].includes(form.cron)
                      ? form.cron
                      : 'custom'
                  }
                  onChange={(e) => {
                    if (e.target.value !== 'custom') set('cron', e.target.value);
                    else set('cron', '0 0 12 * * MON');
                  }}
                >
                  <option value="0 0 9 * * *">매일 오전 9시</option>
                  <option value="0 0 9 * * MON-FRI">평일 오전 9시</option>
                  <option value="0 0 */6 * * *">6시간마다</option>
                  <option value="custom">직접 설정</option>
                </select>
              </label>
              <label className="field">
                시간대
                <select value={form.timezone} onChange={(e) => set('timezone', e.target.value)}>
                  <option>Asia/Seoul</option>
                  <option>UTC</option>
                  <option>America/New_York</option>
                </select>
              </label>
            </div>
            <label className="field">
              일정 표현식
              <input required value={form.cron} onChange={(e) => set('cron', e.target.value)} />
              <small>초 분 시 일 월 요일 · 최소 5분 간격 · 서버가 실행 중일 때 동작</small>
            </label>
          </>
        )}
      </div>
      <div className="form-actions">
        <button type="button" className="button" onClick={onCancel}>
          취소
        </button>
        <button className="button primary" disabled={busy}>
          {busy ? '저장 중…' : '분야 저장'}
        </button>
      </div>
    </form>
  );
}
function SourceForm({
  initial,
  modules,
  onSave,
  onCancel,
  busy,
}: {
  initial?: Source;
  modules: Module[];
  onSave: (p: SourceInput) => Promise<void>;
  onCancel: () => void;
  busy: boolean;
}) {
  const [form, setForm] = useState<SourceInput>(
    initial || { name: '', type: 'RSS', media: 'NEWS', url: '', query: '', channelId: '', enabled: true },
  );
  const selected = modules.find((m) => m.type === form.type);
  const set = (key: keyof SourceInput, value: string | boolean) => setForm((f) => ({ ...f, [key]: value }));
  return (
    <form
      className="form-body"
      onSubmit={(e) => {
        e.preventDefault();
        void onSave(form);
      }}
    >
      <label className="field">
        수집 모듈
        <select value={form.type} onChange={(e) => set('type', e.target.value)}>
          {modules.map((m) => (
            <option value={m.type} key={m.type}>
              {m.name}
              {m.ready ? '' : ' · 인증 설정 필요'}
            </option>
          ))}
        </select>
      </label>
      {selected && !selected.ready && (
        <div className="notice">
          <CircleAlert size={17} />
          <span>{selected.setup}. 출처는 먼저 저장할 수 있습니다.</span>
        </div>
      )}
      <label className="field">
        출처 이름
        <input
          autoFocus
          required
          maxLength={160}
          value={form.name}
          placeholder="예: 기술 뉴스 피드"
          onChange={(e) => set('name', e.target.value)}
        />
      </label>
      {form.type === 'RSS' ? (
        <>
          <label className="field">
            피드 주소
            <input
              type="url"
              required
              maxLength={2048}
              value={form.url}
              placeholder="https://example.com/feed.xml"
              onChange={(e) => set('url', e.target.value)}
            />
            <small>RSS 또는 Atom 주소를 입력하세요. 일반 기사 페이지는 사용할 수 없습니다.</small>
          </label>
          <label className="field">
            매체 분류
            <select value={form.media} onChange={(e) => set('media', e.target.value)}>
              {Object.entries(mediaNames).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </label>
        </>
      ) : (
        <>
          <label className="field">
            검색어 재정의
            <input
              maxLength={500}
              value={form.query}
              placeholder="비워 두면 분야의 포함 키워드 사용"
              onChange={(e) => set('query', e.target.value)}
            />
          </label>
          {form.type === 'YOUTUBE' && (
            <label className="field">
              채널 ID · 선택
              <input
                value={form.channelId}
                placeholder="UC로 시작하는 채널 ID"
                onChange={(e) => set('channelId', e.target.value)}
              />
            </label>
          )}
          <p className="hint">
            {form.type === 'YOUTUBE'
              ? '최신 최대 50건의 영상 제목·설명·링크를 수집합니다. 자막은 수집하지 않습니다.'
              : '검색어당 최신 최대 100건의 제목·발췌문·링크를 수집합니다. 기본 키워드는 최대 5개입니다.'}
          </p>
        </>
      )}
      <label className="check-line">
        <input type="checkbox" checked={form.enabled} onChange={(e) => set('enabled', e.target.checked)} /> 이
        출처에서 수집
      </label>
      <div className="form-actions">
        <button className="button" type="button" onClick={onCancel}>
          취소
        </button>
        <button className="button primary" disabled={busy}>
          {busy ? '저장 중…' : '출처 저장'}
        </button>
      </div>
    </form>
  );
}
export default function App() {
  const [topics, setTopics] = useState<Topic[]>([]),
    [modules, setModules] = useState<Module[]>([]),
    [topicId, setTopicId] = useState('');
  const [tab, setTab] = useState<Tab>('editorial'),
    [sources, setSources] = useState<Source[]>([]),
    [articles, setArticles] = useState<Page<Article>>({ items: [], total: 0, page: 0, size: 20 }),
    [runs, setRuns] = useState<Page<Run>>({ items: [], total: 0, page: 0, size: 20 });
  const [stats, setStats] = useState<Stats>({ running: false, statuses: [], sources: [] });
  const [analysisOpen, setAnalysisOpen] = useState(false);
  const [focusJobId, setFocusJobId] = useState('');
  const [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [toast, setToast] = useState('');
  const [topicModal, setTopicModal] = useState<'new' | 'edit' | null>(null),
    [sourceModal, setSourceModal] = useState<Source | 'new' | null>(null),
    [detail, setDetail] = useState<Article | null>(null);
  const [selected, setSelected] = useState<string[]>([]),
    [q, setQ] = useState(''),
    [search, setSearch] = useState(''),
    [media, setMedia] = useState(''),
    [status, setStatus] = useState(''),
    [sourceFilter, setSourceFilter] = useState(''),
    [from, setFrom] = useState(''),
    [to, setTo] = useState(''),
    [page, setPage] = useState(0),
    [runPage, setRunPage] = useState(0),
    [sort, setSort] = useState('desc'),
    [revision, setRevision] = useState(0);
  const topic = topics.find((t) => t.id === topicId);
  const refresh = () => setRevision((r) => r + 1);
  const loadTopics = useCallback(async () => {
    const [t, m] = await Promise.all([api<Topic[]>('/topics'), api<Module[]>('/modules')]);
    setTopics(t);
    setModules(m);
    setTopicId((id) => (t.some((x) => x.id === id) ? id : t[0]?.id || ''));
    return t;
  }, []);
  useEffect(() => {
    loadTopics()
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [loadTopics]);
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(q);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [q]);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 4500);
    return () => clearTimeout(timer);
  }, [toast]);
  useEffect(() => {
    if (!topicId) return;
    let cancelled = false;
    setLoading(true);
    const query = new URLSearchParams({
      topicId,
      q: search,
      media,
      status,
      source: sourceFilter,
      from,
      to,
      page: String(page),
      sort,
    });
    Promise.all([
      api<Source[]>(`/topics/${topicId}/sources`),
      api<Page<Article>>('/articles?' + query),
      api<Page<Run>>(`/runs?topicId=${topicId}&page=${runPage}`),
      api<Stats>(`/topics/${topicId}/stats`),
    ])
      .then(([s, a, r, st]) => {
        if (!cancelled) {
          setSources(s);
          setArticles(a);
          setRuns(r);
          setStats(st);
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [topicId, search, media, status, sourceFilter, from, to, page, runPage, sort, revision]);
  useEffect(() => {
    setSelected([]);
  }, [topicId, search, media, status, sourceFilter, from, to]);
  useEffect(() => {
    if (!topicId) return;
    const timer = setInterval(
      () => {
        refresh();
        void loadTopics().catch(() => {});
      },
      stats.running ? 2500 : 15000,
    );
    return () => clearInterval(timer);
  }, [stats.running, topicId, loadTopics]);
  function chooseTopic(id: string) {
    setAnalysisOpen(false);
    setFocusJobId('');
    setTopicId(id);
    setPage(0);
    setRunPage(0);
    setQ('');
    setSearch('');
    setMedia('');
    setStatus('');
    setSourceFilter('');
    setFrom('');
    setTo('');
    setSelected([]);
    setDetail(null);
    setStats({ running: false, statuses: [], sources: [] });
    setArticles({ items: [], total: 0, page: 0, size: 20 });
    setSources([]);
    setRuns({ items: [], total: 0, page: 0, size: 20 });
  }
  async function action(fn: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : '요청에 실패했습니다.');
    } finally {
      setBusy(false);
    }
  }
  async function saveTopic(p: TopicInput) {
    await action(async () => {
      const t = await api<Topic>(
        topicModal === 'edit' ? `/topics/${topicId}` : '/topics',
        topicModal === 'edit' ? 'PUT' : 'POST',
        p,
      );
      await loadTopics();
      chooseTopic(t.id);
      setTopicModal(null);
      setToast('분야를 저장했습니다.');
      refresh();
    });
  }
  async function saveSource(p: SourceInput) {
    await action(async () => {
      await api(
        `/topics/${topicId}/sources${sourceModal && sourceModal !== 'new' ? '/' + sourceModal.id : ''}`,
        sourceModal === 'new' ? 'POST' : 'PUT',
        p,
      );
      setSourceModal(null);
      await loadTopics();
      refresh();
      setToast('출처를 저장했습니다.');
    });
  }
  async function collect() {
    await action(async () => {
      await api(`/topics/${topicId}/collect`, 'POST');
      setStats((s) => ({ ...s, running: true }));
      setRunPage(0);
      setTab('history');
      setToast('수집을 시작했습니다. 실행 이력에서 진행 상황을 확인하세요.');
      refresh();
    });
  }
  async function changeStatus(ids: string[], next: string) {
    await action(async () => {
      await api('/articles/status', 'PATCH', { ids, topicId, status: next });
      setDetail((d) => (d && ids.includes(d.id) ? { ...d, status: next } : d));
      refresh();
      setToast(`${ids.length}개 항목을 ${statusNames[next]} 처리했습니다.`);
    });
  }
  async function deleteTopic() {
    if (!topic || !window.confirm(`“${topic.name}” 분야와 출처·실행 이력·분야 연결을 삭제할까요?`)) return;
    await action(async () => {
      await api(`/topics/${topicId}`, 'DELETE');
      await loadTopics();
      chooseTopic('');
      setTopicId('');
      const t = await loadTopics();
      if (t[0]) chooseTopic(t[0].id);
      setToast('분야를 삭제했습니다.');
    });
  }
  function count(s: string) {
    return stats.statuses.find((v) => v.status === s)?.count || 0;
  }
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Issue Desk 홈">
          <span className="brand-icon">
            <Layers3 size={24} />
          </span>
          <span>
            issue<span className="brand-light">desk</span>
            <small>COLLECTION WORKSPACE</small>
          </span>
        </a>
        <div className="sidebar-label">
          내 분야 <span>{topics.length}</span>
        </div>
        <button className="new-topic" onClick={() => setTopicModal('new')}>
          <Plus size={17} /> 새 분야 만들기
        </button>
        <nav className="topic-list" aria-label="분야">
          {topics.map((t) => (
            <button
              className={`topic-link ${topicId === t.id ? 'active' : ''}`}
              key={t.id}
              onClick={() => chooseTopic(t.id)}
            >
              <span className={`topic-dot ${!t.active ? 'paused' : ''}`} />
              <span>{t.name}</span>
              <small>{t.articleCount}</small>
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div>
            <span className="live-dot" /> 로컬 작업 공간
          </div>
          <p>
            관심 있는 소식부터
            <br />
            차곡차곡 모아보세요.
          </p>
          <span className="version">
            ISSUE DESK <b>v0.1</b>
          </span>
        </div>
      </aside>
      <main className="main">
        <header className="topbar">
          <span>
            작업 공간 <ChevronRight size={14} /> <strong>{topic?.name || '분야 관리'}</strong>
          </span>
          <span className="topbar-right">
            <span className="outline-dot" /> 수집 워크스페이스
          </span>
        </header>
        {error && (
          <div className="error-banner" role="alert">
            <CircleAlert size={18} />
            <span>{error}</span>
            <button
              onClick={() => {
                setError('');
                void loadTopics().catch((e) => setError(e.message));
                refresh();
              }}
            >
              다시 시도
            </button>
            <button aria-label="오류 닫기" onClick={() => setError('')}>
              <X size={16} />
            </button>
          </div>
        )}
        {!topic ? (
          <div className="welcome">
            <span className="eyebrow">YOUR FIRST COLLECTION</span>
            <div className="welcome-symbol">
              <FolderOpen size={42} />
            </div>
            <h1>어떤 소식을 모아볼까요?</h1>
            <p>
              분야와 관심 키워드를 정하면 원문 발견과 소재 추천을 시작할 수 있습니다.
              <br />
              기사, 블로그, 유튜브를 한곳에서 살펴볼 수 있습니다.
            </p>
            <button className="button primary" onClick={() => setTopicModal('new')}>
              <Plus size={18} /> 첫 분야 만들기
            </button>
            <div className="welcome-steps">
              <span>
                <b>01</b> 분야 만들기
              </span>
              <ChevronRight size={18} />
              <span>
                <b>02</b> 원문 자동 발견
              </span>
              <ChevronRight size={18} />
              <span>
                <b>03</b> 추천 · 글 작성
              </span>
            </div>
          </div>
        ) : (
          <>
            <section className="page-head">
              <div>
                <div className="eyebrow">
                  TOPIC COLLECTION{' '}
                  <span className={`badge ${topic.active ? 'green' : 'gray'}`}>
                    {topic.active ? '활성' : '중지'}
                  </span>
                </div>
                <h1>{topic.name}</h1>
                <details open={tab !== 'editorial'}><summary>분야 설명과 관심 태그</summary>
                <p>{topic.description || '분야 설정에서 수집할 내용을 설명해 주세요.'}</p>
                <div className="head-tags">
                  {topic.tags.map((t) => (
                    <span key={t}># {t}</span>
                  ))}
                </div>
                </details>
              </div>
              <div className="head-actions">
                <button className="button" onClick={() => setTopicModal('edit')}>
                  <SlidersHorizontal size={16} /> 분야 편집
                </button>
                {tab !== 'editorial' && <button
                  className="button primary"
                  disabled={busy || stats.running || !topic.active || !sources.some((s) => s.enabled)}
                  onClick={() => void collect()}
                >
                  <RefreshCw size={16} className={stats.running ? 'spin' : ''} />
                  {stats.running ? '수집 중…' : '지금 수집'}
                </button>}
              </div>
            </section>
            {tab !== 'editorial' && <div className="stat-grid">
              <div className="stat">
                <span>
                  전체 수집 자료
                  <Layers3 size={17} />
                </span>
                <strong>
                  {topic.articleCount.toLocaleString()}
                  <small>건</small>
                </strong>
              </div>
              <div className="stat">
                <span>
                  미확인 자료
                  <Eye size={17} />
                </span>
                <strong>
                  {count('UNREAD').toLocaleString()}
                  <small>건</small>
                </strong>
              </div>
              <div className="stat">
                <span>
                  보관한 자료
                  <Bookmark size={17} />
                </span>
                <strong>
                  {count('SAVED').toLocaleString()}
                  <small>건</small>
                </strong>
              </div>
              <div className="stat schedule-stat">
                <span>
                  다음 수집
                  <History size={17} />
                </span>
                <strong>{topic.active && topic.scheduleEnabled ? date(topic.nextRun) : '수동 수집'}</strong>
                <small>{topic.scheduleEnabled ? topic.timezone : '필요할 때 직접 실행'}</small>
              </div>
            </div>
            }
            <div className="tabbar" role="tablist" aria-label="분야 메뉴">
              {tabs.map((t) => (
                <button
                  role="tab"
                  aria-selected={tab === t.id}
                  key={t.id}
                  className={tab === t.id ? 'active' : ''}
                  onClick={() => setTab(t.id)}
                >
                  <t.icon size={17} />
                  {t.name}
                  {t.id === 'sources' && <span>{sources.length}</span>}
                </button>
              ))}
            </div>
            <section className="content-area" aria-busy={loading}>
              {tab === 'results' && (
                <>
                  <div className="section-heading">
                    <div>
                      <h2>수집한 자료</h2>
                      <p>지금 수집하면 예약 시간과 관계없이 활성 출처에서 최신 자료를 가져옵니다.</p>
                    </div>
                    <div className="collection-actions">
                      <button
                        className="button primary"
                        disabled={busy || stats.running || !topic.active || !sources.some((s) => s.enabled)}
                        onClick={() => void collect()}
                      >
                        <RefreshCw size={16} className={stats.running ? 'spin' : ''} />
                        {stats.running ? '수집 중…' : '지금 수집'}
                      </button>
                      <button
                        className="icon-button"
                        aria-label="결과 새로고침"
                        onClick={() => {
                          refresh();
                          void loadTopics().catch((e) => setError(e.message));
                        }}
                      >
                        <RefreshCw size={17} className={loading ? 'spin' : ''} />
                      </button>
                    </div>
                  </div>
                  <div className="filter-row">
                    <label className="search-box">
                      <Search size={17} />
                      <input
                        aria-label="제목 검색"
                        placeholder="제목으로 검색"
                        value={q}
                        onChange={(e) => setQ(e.target.value)}
                      />
                    </label>
                    <select
                      aria-label="매체 필터"
                      value={media}
                      onChange={(e) => {
                        setMedia(e.target.value);
                        setPage(0);
                      }}
                    >
                      <option value="">전체 매체</option>
                      {Object.entries(mediaNames).map(([k, v]) => (
                        <option key={k} value={k}>
                          {v}
                        </option>
                      ))}
                    </select>
                    <select
                      aria-label="출처 필터"
                      value={sourceFilter}
                      onChange={(e) => {
                        setSourceFilter(e.target.value);
                        setPage(0);
                      }}
                    >
                      <option value="">전체 출처</option>
                      {stats.sources.map((s) => (
                        <option key={s.sourceName}>{s.sourceName}</option>
                      ))}
                    </select>
                    <select
                      aria-label="상태 필터"
                      value={status}
                      onChange={(e) => {
                        setStatus(e.target.value);
                        setPage(0);
                      }}
                    >
                      <option value="">전체 상태 · 숨김 제외</option>
                      {['UNREAD', 'READ', 'SAVED', 'HIDDEN'].map((k) => (
                        <option key={k} value={k}>
                          {statusNames[k]}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="filter-secondary">
                    <span>
                      <input
                        aria-label="게시 시작일"
                        type="date"
                        value={from}
                        max={to || undefined}
                        onChange={(e) => {
                          setFrom(e.target.value);
                          setPage(0);
                        }}
                      />
                      <span>—</span>
                      <input
                        aria-label="게시 종료일"
                        type="date"
                        value={to}
                        min={from || undefined}
                        onChange={(e) => {
                          setTo(e.target.value);
                          setPage(0);
                        }}
                      />
                    </span>
                    <span className="result-count">
                      총 <b>{articles.total.toLocaleString()}</b>건{' '}
                      <button
                        className="text-button"
                        onClick={() => {
                          setSort((s) => (s === 'desc' ? 'asc' : 'desc'));
                          setPage(0);
                        }}
                      >
                        {sort === 'desc' ? '최신순' : '오래된순'}
                        <ChevronDown size={14} />
                      </button>
                    </span>
                  </div>
                  {selected.length > 0 && (
                    <div className="bulk-bar">
                      <strong>{selected.length}개 선택</strong>
                      <button onClick={() => void changeStatus(selected, 'READ')} disabled={busy}>
                        <Check size={15} />
                        읽음
                      </button>
                      <button onClick={() => void changeStatus(selected, 'SAVED')} disabled={busy}>
                        <Bookmark size={15} />
                        보관
                      </button>
                      <button onClick={() => void changeStatus(selected, 'HIDDEN')} disabled={busy}>
                        <EyeOff size={15} />
                        숨김
                      </button>
                      <button onClick={() => setSelected([])}>선택 해제</button>
                    </div>
                  )}
                  <div className="analysis-launch">
                    <div>
                      <strong>선택한 자료로 블로그 소재 찾기 · {selected.length}/20건</strong>
                      <p>
                        게시 기간·매체로 범위를 좁힌 뒤 체크하세요. 페이지를 이동해도 선택은 유지되며, 필터를
                        바꾸면 초기화됩니다.
                      </p>
                    </div>
                    <button
                      className="button primary"
                      disabled={!selected.length || busy || loading}
                      onClick={() => setAnalysisOpen(true)}
                    >
                      <Sparkles size={17} /> 선택 자료 AI 분석
                    </button>
                  </div>
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th className="check-cell">
                            <input
                              type="checkbox"
                              aria-label="현재 페이지 전체 선택"
                              checked={
                                articles.items.length > 0 &&
                                articles.items.every((a) => selected.includes(a.id))
                              }
                              onChange={(e) =>
                                setSelected((prev) =>
                                  e.target.checked
                                    ? [...new Set([...prev, ...articles.items.map((a) => a.id)])].slice(0, 20)
                                    : prev.filter((id) => !articles.items.some((a) => a.id === id)),
                                )
                              }
                            />
                          </th>
                          <th>자료 제목 / 출처</th>
                          <th>매체</th>
                          <th>일치 키워드</th>
                          <th>게시일</th>
                          <th>상태</th>
                          <th aria-label="원문" />
                        </tr>
                      </thead>
                      <tbody>
                        {articles.items.map((a) => (
                          <tr key={a.id} className={selected.includes(a.id) ? 'selected' : ''}>
                            <td>
                              <input
                                type="checkbox"
                                aria-label={`${a.title} 선택`}
                                checked={selected.includes(a.id)}
                                disabled={!selected.includes(a.id) && selected.length >= 20}
                                onChange={(e) =>
                                  setSelected((s) =>
                                    e.target.checked
                                      ? [...new Set([...s, a.id])].slice(0, 20)
                                      : s.filter((v) => v !== a.id),
                                  )
                                }
                              />
                            </td>
                            <td className="title-cell">
                              <button onClick={() => setDetail(a)}>{a.title}</button>
                              <small>
                                {a.sourceName}
                                {a.author ? ' · ' + a.author : ''}
                              </small>
                            </td>
                            <td>
                              <span className={`media-badge ${a.media.toLowerCase()}`}>
                                {mediaNames[a.media]}
                              </span>
                            </td>
                            <td>
                              <div className="keyword-cell">
                                {a.matchedKeywords.length ? (
                                  a.matchedKeywords.slice(0, 2).map((k) => (
                                    <span className="chip" key={k}>
                                      {k}
                                    </span>
                                  ))
                                ) : (
                                  <span className="muted">—</span>
                                )}
                              </div>
                            </td>
                            <td className="date-cell">{date(a.publishedAt)}</td>
                            <td>
                              <span className={`status ${a.status.toLowerCase()}`}>
                                {statusNames[a.status]}
                              </span>
                            </td>
                            <td>
                              <a
                                href={a.url}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="icon-button"
                                aria-label={`${a.title} 원문 열기`}
                              >
                                <ArrowUpRight size={17} />
                              </a>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {!articles.items.length && (
                      <div className="empty-state">
                        <span className="empty-icon">
                          <Search size={27} />
                        </span>
                        <h3>
                          {loading
                            ? '자료를 불러오고 있습니다'
                            : topic.articleCount
                              ? '조건에 맞는 자료가 없습니다'
                              : '첫 수집을 시작해 보세요'}
                        </h3>
                        <p>
                          {topic.articleCount
                            ? '검색어나 필터를 변경해 보세요.'
                            : sources.length
                              ? '지금 수집을 누르면 연결한 출처에서 자료를 가져옵니다.'
                              : '수집 출처를 추가하면 이곳에 자료가 모입니다.'}
                        </p>
                        {!sources.length && (
                          <button
                            className="button"
                            onClick={() => {
                              setTab('sources');
                              setSourceModal('new');
                            }}
                          >
                            <Plus size={16} /> 출처 추가
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                  <div className="pagination">
                    <span>
                      {articles.total
                        ? `${page * 20 + 1}–${Math.min((page + 1) * 20, articles.total)} / ${articles.total}건`
                        : '0건'}
                      <small>원문 전체가 아닌 제공된 설명·발췌문을 수집합니다.</small>
                    </span>
                    <div>
                      <button
                        className="icon-button"
                        aria-label="이전 페이지"
                        disabled={page === 0 || loading}
                        onClick={() => setPage((p) => p - 1)}
                      >
                        <ChevronLeft size={17} />
                      </button>
                      <span>{page + 1}</span>
                      <button
                        className="icon-button"
                        aria-label="다음 페이지"
                        disabled={(page + 1) * 20 >= articles.total || loading}
                        onClick={() => setPage((p) => p + 1)}
                      >
                        <ChevronRight size={17} />
                      </button>
                    </div>
                  </div>
                </>
              )}
              {tab === 'editorial' && <EditorialWorkbench key={topicId} topicId={topicId} onSources={()=>setTab('sources')}/>}
              {tab === 'analysis' && (
                <AnalysisWorkspace
                  key={topicId}
                  topicId={topicId}
                  focusJobId={focusJobId}
                  onCollectTab={() => setTab('results')}
                />
              )}
              {tab === 'sources' && (
                <>
                  <div className="section-heading">
                    <div>
                      <h2>
                        수집 출처 <span className="muted">{sources.length}</span>
                      </h2>
                      <p>출처를 저장한 뒤 지금 수집을 누르면 즉시 실행됩니다. 예약 일정은 유지됩니다.</p>
                    </div>
                    <div className="collection-actions">
                      <button className="button" onClick={() => setSourceModal('new')}>
                        <Plus size={17} /> 출처 추가
                      </button>
                      <button
                        className="button primary"
                        disabled={busy || stats.running || !topic.active || !sources.some((s) => s.enabled)}
                        onClick={() => void collect()}
                      >
                        <RefreshCw size={16} className={stats.running ? 'spin' : ''} />
                        {stats.running ? '수집 중…' : '지금 수집'}
                      </button>
                    </div>
                  </div>
                  <div className="module-strip">
                    {modules.map((m) => (
                      <div key={m.type}>
                        <Radio size={17} />
                        <strong>{m.name}</strong>
                        <span className={m.ready ? 'ready' : 'muted'}>
                          {m.ready ? '사용 가능' : '인증 필요'}
                        </span>
                      </div>
                    ))}
                  </div>
                  {!sources.length ? (
                    <div className="empty-state">
                      <Rss size={32} />
                      <h3>연결된 출처가 없습니다</h3>
                      <p>RSS 주소나 검색 모듈을 추가해 수집 범위를 정하세요.</p>
                    </div>
                  ) : (
                    <div className="source-list">
                      {sources.map((s) => (
                        <div className="source-card" key={s.id}>
                          <div className={`source-icon ${s.type.toLowerCase()}`}>
                            <Rss size={23} />
                          </div>
                          <div className="source-content">
                            <h3>
                              {s.name}
                              <span className={`badge ${s.enabled ? 'green' : 'gray'}`}>
                                {s.enabled ? '활성' : '중지'}
                              </span>
                            </h3>
                            <p>{s.type === 'RSS' ? s.url : s.query || '분야 키워드 사용'}</p>
                            <small>
                              {modules.find((m) => m.type === s.type)?.name} · 마지막 성공:{' '}
                              {s.lastSuccess ? date(s.lastSuccess) : '아직 수집하지 않음'}
                            </small>
                          </div>
                          <button
                            className="icon-button"
                            aria-label={`${s.name} 편집`}
                            disabled={stats.running}
                            onClick={() => setSourceModal(s)}
                          >
                            <Pencil size={17} />
                          </button>
                          <button
                            className="icon-button danger"
                            aria-label={`${s.name} 삭제`}
                            disabled={busy || stats.running}
                            onClick={() => {
                              if (
                                window.confirm(`“${s.name}” 출처를 삭제할까요? 기존 수집 자료는 유지됩니다.`)
                              )
                                void action(async () => {
                                  await api(`/topics/${topicId}/sources/${s.id}`, 'DELETE');
                                  await loadTopics();
                                  refresh();
                                });
                            }}
                          >
                            <Trash2 size={17} />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                  <div className="info-note">
                    <CircleAlert size={17} />
                    <p>
                      RSS는 포함 키워드 중 하나라도 일치하는 자료를 수집합니다. 검색 모듈은 지정된 검색어로
                      조회하며, 모든 모듈에 제외 키워드가 적용됩니다.
                    </p>
                  </div>
                </>
              )}
              {tab === 'settings' && (
                <>
                  <div className="section-heading">
                    <div>
                      <h2>분야 설정</h2>
                      <p>무엇을 모을지, 언제 수집할지 정하세요.</p>
                    </div>
                    <button className="button" onClick={() => setTopicModal('edit')}>
                      <Pencil size={16} /> 편집
                    </button>
                  </div>
                  <div className="settings-grid">
                    <section className="settings-card">
                      <h3>수집 기준</h3>
                      <dl>
                        <dt>상세 지침</dt>
                        <dd className="prewrap">{topic.instructions || '등록된 지침이 없습니다.'}</dd>
                        <dt>포함 키워드</dt>
                        <dd>
                          {topic.keywords.length
                            ? topic.keywords.map((k) => (
                                <span className="chip" key={k}>
                                  {k}
                                </span>
                              ))
                            : '없음 · RSS 전체 항목'}
                        </dd>
                        <dt>제외 키워드</dt>
                        <dd>
                          {topic.exclusions.length
                            ? topic.exclusions.map((k) => (
                                <span className="chip" key={k}>
                                  {k}
                                </span>
                              ))
                            : '없음'}
                        </dd>
                        <dt>관리 태그</dt>
                        <dd>{topic.tags.join(', ') || '없음'}</dd>
                        <dt>언어 / 지역</dt>
                        <dd>
                          {topic.language} / {topic.region}
                        </dd>
                      </dl>
                    </section>
                    <section className="settings-card">
                      <h3>수집 일정</h3>
                      <dl>
                        <dt>실행 방식</dt>
                        <dd>{topic.scheduleEnabled ? '정기 수집' : '수동 수집'}</dd>
                        <dt>일정</dt>
                        <dd>
                          <code>{topic.cron}</code>
                        </dd>
                        <dt>시간대</dt>
                        <dd>{topic.timezone}</dd>
                        <dt>다음 실행</dt>
                        <dd>{topic.nextRun ? date(topic.nextRun) : '설정되지 않음'}</dd>
                      </dl>
                      <p className="hint">
                        정기 수집은 서버 실행 중에 동작합니다. 서버 중단 중 놓친 일정은 재시작 후 한 번
                        실행합니다.
                      </p>
                    </section>
                  </div>
                  <div className="danger-zone">
                    <div>
                      <h3>분야 삭제</h3>
                      <p>분야의 출처, 실행 이력, 자료 연결이 삭제됩니다.</p>
                    </div>
                    <button
                      className="button danger"
                      disabled={busy || stats.running}
                      onClick={() => void deleteTopic()}
                    >
                      <Trash2 size={16} /> 분야 삭제
                    </button>
                  </div>
                </>
              )}
              {tab === 'history' && (
                <>
                  <div className="section-heading">
                    <div>
                      <h2>실행 이력</h2>
                      <p>출처별 수집 건수와 실패 원인을 확인하세요.</p>
                    </div>
                    {stats.running && (
                      <span className="badge green">
                        <RefreshCw size={13} className="spin" /> 수집 진행 중
                      </span>
                    )}
                  </div>
                  {!runs.items.length ? (
                    <div className="empty-state">
                      <History size={30} />
                      <h3>아직 실행 이력이 없습니다</h3>
                      <p>첫 수집을 실행하면 결과가 여기에 기록됩니다.</p>
                    </div>
                  ) : (
                    <div className="run-list">
                      {runs.items.map((r) => (
                        <div className="run-card" key={r.id}>
                          <div className="run-head">
                            <span className={`status ${r.status.toLowerCase()}`}>
                              {statusNames[r.status]}
                            </span>
                            <strong>{date(r.startedAt)}</strong>
                            <span className="muted">
                              {r.triggerType === 'MANUAL' ? '직접 실행' : '예약 실행'}
                            </span>
                            <span className="run-id">{r.id.slice(0, 8)}</span>
                          </div>
                          <p>{r.message || '수집을 준비하고 있습니다.'}</p>
                          {r.sources.map((s) => (
                            <div className="run-source" key={s.id}>
                              <div>
                                <strong>{s.sourceName}</strong>
                                <span className={`status ${s.status.toLowerCase()}`}>
                                  {statusNames[s.status]}
                                </span>
                              </div>
                              <div className="run-counts">
                                <span>
                                  조회 <b>{s.fetched}</b>
                                </span>
                                <span>
                                  신규 <b>{s.added}</b>
                                </span>
                                <span>
                                  중복 <b>{s.duplicates}</b>
                                </span>
                                <span>
                                  제외 <b>{s.filtered}</b>
                                </span>
                              </div>
                              {s.message && (
                                <small className={s.status === 'FAILED' ? 'danger' : ''}>{s.message}</small>
                              )}
                            </div>
                          ))}
                        </div>
                      ))}
                    </div>
                  )}
                  <div className="pagination">
                    <span>총 {runs.total}회</span>
                    <div>
                      <button
                        className="icon-button"
                        aria-label="이전 실행 이력"
                        disabled={!runPage}
                        onClick={() => setRunPage((p) => p - 1)}
                      >
                        <ChevronLeft size={17} />
                      </button>
                      <span>{runPage + 1}</span>
                      <button
                        className="icon-button"
                        aria-label="다음 실행 이력"
                        disabled={(runPage + 1) * 20 >= runs.total}
                        onClick={() => setRunPage((p) => p + 1)}
                      >
                        <ChevronRight size={17} />
                      </button>
                    </div>
                  </div>
                </>
              )}
            </section>
          </>
        )}
        <footer className="page-footer">
          <span>ISSUE DESK / 수집 작업실</span>
          <span>자료 수집 → 검토 → 보관</span>
        </footer>
      </main>
      {analysisOpen && (
        <AnalysisDialog
          topicId={topicId}
          ids={[...selected]}
          onClose={() => setAnalysisOpen(false)}
          onStarted={(id) => {
            setAnalysisOpen(false);
            setFocusJobId(id);
            setTab('analysis');
            setSelected([]);
          }}
        />
      )}
      {topicModal && (
        <Modal
          title={topicModal === 'new' ? '새 분야 만들기' : '분야 편집'}
          onClose={() => {
            if (!busy) setTopicModal(null);
          }}
          wide
        >
          <TopicForm
            initial={topicModal === 'edit' && topic ? topic : emptyTopic}
            onSave={saveTopic}
            onCancel={() => setTopicModal(null)}
            busy={busy}
          />
          {error && (
            <p className="modal-error" role="alert">
              {error}
            </p>
          )}
        </Modal>
      )}
      {sourceModal && (
        <Modal
          title={sourceModal === 'new' ? '수집 출처 추가' : '수집 출처 편집'}
          onClose={() => {
            if (!busy) setSourceModal(null);
          }}
        >
          <SourceForm
            initial={sourceModal === 'new' ? undefined : sourceModal}
            modules={modules}
            onSave={saveSource}
            onCancel={() => setSourceModal(null)}
            busy={busy}
          />
          {error && (
            <p className="modal-error" role="alert">
              {error}
            </p>
          )}
        </Modal>
      )}
      {detail && (
        <Modal title="자료 상세" onClose={() => setDetail(null)} wide>
          <div className="detail-body">
            <div className="detail-meta">
              <span className={`media-badge ${detail.media.toLowerCase()}`}>{mediaNames[detail.media]}</span>
              <span>{detail.sourceName}</span>
              <span className={`status ${detail.status.toLowerCase()}`}>{statusNames[detail.status]}</span>
            </div>
            <h2>{detail.title}</h2>
            <p className="muted">
              게시 {date(detail.publishedAt)} · 수집 {date(detail.collectedAt)}
            </p>
            <div className="excerpt">
              <span className="eyebrow">제공된 설명 · 발췌문</span>
              <p>{detail.excerpt || '출처에서 설명을 제공하지 않았습니다. 원문에서 내용을 확인해 주세요.'}</p>
            </div>
            <div className="head-tags">
              {detail.matchedKeywords.map((k) => (
                <span key={k}># {k}</span>
              ))}
            </div>
            <p className="hint">자동 생성 요약이 아닙니다. 정확한 맥락은 원문에서 확인하세요.</p>
            <div className="detail-actions">
              <button
                className="button"
                disabled={busy}
                onClick={() => void changeStatus([detail.id], detail.status === 'READ' ? 'UNREAD' : 'READ')}
              >
                <Eye size={16} />
                {detail.status === 'READ' ? '미확인으로' : '읽음 처리'}
              </button>
              <button
                className="button"
                disabled={busy}
                onClick={() => void changeStatus([detail.id], detail.status === 'SAVED' ? 'UNREAD' : 'SAVED')}
              >
                <Bookmark size={16} />
                {detail.status === 'SAVED' ? '보관 해제' : '보관'}
              </button>
              <a className="button primary" href={detail.url} target="_blank" rel="noopener noreferrer">
                원문 열기
                <ArrowUpRight size={17} />
              </a>
            </div>
          </div>
        </Modal>
      )}
      {toast && (
        <div className="toast" role="status">
          <Check size={18} />
          {toast}
        </div>
      )}
    </div>
  );
}
