import { useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowUpRight,
  Check,
  CircleAlert,
  FileText,
  Layers3,
  RefreshCw,
  Sparkles,
  X,
} from 'lucide-react';
import { api, date } from './api';
import type { Page } from './api';
import './analysis.css';
import { DraftComposer } from './DraftComposer';

type Source = {
  id: string;
  title: string;
  url: string;
  sourceName: string;
  publishedAt?: string;
  coverage: string;
  excerpt: string;
  truncated: boolean;
};
type Input = { topicName: string; mode: string; direction: string; sources: Source[] };
type Preview = {
  input: Input;
  inputChars: number;
  maxInputChars: number;
  maxArticles: number;
  excerptLimit: number;
  truncatedCount: number;
  topicTruncated: boolean;
  fingerprint: string;
  cachedJobId?: string;
};
type Issue = {
  title: string;
  summary: string;
  category: string;
  categoryReason: string;
  angle: string;
  audience: string;
  suggestedTitles: string[];
  outline: string[];
  keyPoints: string[];
  tags: string[];
  sourceIds: string[];
  uncertainties: string[];
};
type Job = {
  id: string;
  status: string;
  mode: string;
  direction: string;
  articleCount: number;
  inputChars: number;
  inputTokens?: number;
  outputTokens?: number;
  cachedTokens?: number;
  message: string;
  createdAt: string;
  finishedAt?: string;
  input?: Input;
  result?: { issues: Issue[]; excluded: { sourceId: string; reason: string }[] };
};
const names: Record<string, string> = {
  QUEUED: '분석 대기',
  RUNNING: '분석 중',
  SUCCESS: '분석 완료',
  FAILED: '분석 실패',
  AUTH_REQUIRED: '로그인 필요',
  LIMIT_REACHED: '사용량 한도 도달',
  CANCELLED: '분석 중단',
};
const running = (job: Job) => ['QUEUED', 'RUNNING'].includes(job.status);

export function AnalysisDialog({
  topicId,
  ids,
  onClose,
  onStarted,
}: {
  topicId: string;
  ids: string[];
  onClose: () => void;
  onStarted: (id: string) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [mode, setMode] = useState('QUICK'),
    [direction, setDirection] = useState(''),
    [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [ready, setReady] = useState<{ ready: boolean; message: string } | null>(null);
  useEffect(() => {
    ref.current?.showModal();
    let current = true;
    api<{ ready: boolean; message: string }>('/analysis/status')
      .then((v) => {
        if (current) setReady(v);
      })
      .catch((e) => {
        if (current) {
          setReady({ready:false,message:'서버 연결 실패 · Codex 로그인 상태를 확인하지 못했습니다.'});
          setError(e.message);
        }
      });
    return () => {
      current = false;
    };
  }, []);
  async function prepare() {
    setBusy(true);
    setError('');
    try {
      setPreview(
        await api<Preview>('/analysis/preview', 'POST', { topicId, articleIds: ids, mode, direction }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function start() {
    if (!preview) return;
    setBusy(true);
    setError('');
    try {
      const result = await api<{ jobId: string; cached: boolean }>('/analysis/jobs', 'POST', {
        request: { topicId, articleIds: ids, mode, direction },
        fingerprint: preview.fingerprint,
      });
      onStarted(result.jobId);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <dialog
      ref={ref}
      className="modal wide analysis-dialog"
      onCancel={(e) => {
        if (busy) e.preventDefault();
        else onClose();
      }}
    >
      <div className="modal-head">
        <h2>
          <Sparkles size={19} /> 선택 자료 AI 분석
        </h2>
        <button className="icon-button" aria-label="분석 설정 닫기" disabled={busy} onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      <div className="form-body">
        <div className="analysis-intro">
          <span className="badge green">직접 실행</span>
          <strong>선택한 {ids.length}건만 분석합니다</strong>
          <p>기간·매체·출처로 좁힌 자료를 이슈별로 정리하고, 한국어 블로그 기획을 제안합니다.</p>
        </div>
        <div className="analysis-mode-options">
          {[
            { id: 'QUICK', name: '빠른 정리', text: '최대 20건 · 자료당 발췌문 600자' },
            { id: 'DETAILED', name: '상세 기획', text: '최대 10건 · 자료당 발췌문 2,000자' },
          ].map((m) => (
            <label key={m.id} className={mode === m.id ? 'chosen' : ''}>
              <input
                disabled={busy}
                type="radio"
                name="analysis-mode"
                value={m.id}
                checked={mode === m.id}
                onChange={() => {
                  setMode(m.id);
                  setPreview(null);
                }}
              />
              <span>
                <strong>{m.name}</strong>
                <small>{m.text}</small>
              </span>
            </label>
          ))}
        </div>
        <label className="field">
          이번 분석 디렉션 · 선택
          <textarea
            disabled={busy}
            value={direction}
            maxLength={1000}
            rows={3}
            placeholder="예: 실무 개발자 대상. 도구별 적용 사례 중심으로 나누고, 튜토리얼과 기술 동향 카테고리를 추천해줘."
            onChange={(e) => {
              setDirection(e.target.value);
              setPreview(null);
            }}
          />
          <small>분야 설명·상세 지침도 함께 반영합니다.</small>
        </label>
        <div className="analysis-account">
          <span className={`badge ${ready?.ready ? 'green' : 'gray'}`}>
            {ready?.ready ? '구독 로그인' : '연결 확인'}
          </span>
          <span>{ready?.message || 'Codex 로그인 상태 확인 중…'}</span>
        </div>
        {preview && (
          <section className="analysis-preview" aria-label="분석 범위 확인">
            <h3>전달 범위</h3>
            <div className="analysis-metrics">
              <span>
                자료 <b>{preview.input.sources.length}건</b>
              </span>
              <span>
                전달 입력 <b>{preview.inputChars.toLocaleString()}자</b>
              </span>
              <span>
                입력 상한 <b>{preview.maxInputChars.toLocaleString()}자</b>
              </span>
            </div>
            <p>
              지침과 자료를 합한 글자 수입니다. 실제 토큰 수·추론 사용량은 다르며, 구독 사용량이 소모됩니다.
            </p>
            <p>현재는 저장된 제목과 발췌문만 사용합니다. 원문 본문·유튜브 자막을 새로 가져오지 않습니다.</p>
            {(preview.truncatedCount > 0 || preview.topicTruncated) && (
              <p className="analysis-warning">
                긴 발췌문 {preview.truncatedCount}건을 분량 상한에 맞춰 줄였습니다.
                {preview.topicTruncated ? ' 긴 분야 설명·지침도 축약 전달됩니다.' : ''}
              </p>
            )}
            <ul className="analysis-preview-sources">
              {preview.input.sources.map((s) => (
                <li key={s.id}>
                  <span>{s.title}</span>
                  <small>
                    {s.excerpt.length.toLocaleString()}자{s.truncated ? ' · 일부 발췌' : ''}
                    {!s.excerpt ? ' · 제목만' : ''}
                  </small>
                </li>
              ))}
            </ul>
            {preview.cachedJobId && (
              <div className="notice">
                <Check size={17} /> 같은 조건의 완료 결과가 있어 추가 분석 없이 기존 결과를 엽니다.
              </div>
            )}
          </section>
        )}
        {error && (
          <p className="analysis-error" role="alert">
            {error}
          </p>
        )}
        <div className="info-note">
          <CircleAlert size={17} />
          <p>
            예약 분석과 자동 재시도는 없습니다. 한도 도달 시 멈추며 API 키 과금으로 전환하지 않습니다. 이미
            구매한 추가 크레딧의 소비 여부는 Codex 계정 설정을 따릅니다.
          </p>
        </div>
        <div className="form-actions">
          <button className="button" disabled={busy} onClick={onClose}>
            취소
          </button>
          {!preview ? (
            <button
              className="button primary"
              disabled={busy || !ids.length || (mode === 'DETAILED' && ids.length > 10)}
              onClick={() => void prepare()}
            >
              {busy ? '확인 중…' : '범위 확인 · AI 사용 없음'}
            </button>
          ) : (
            <button
              className="button primary"
              disabled={busy || (!ready?.ready && !preview.cachedJobId)}
              onClick={() => void start()}
            >
              <Sparkles size={16} />
              {busy ? '요청 중…' : preview.cachedJobId ? '기존 분석 결과 열기' : '이 범위로 분석 시작'}
            </button>
          )}
        </div>
        {mode === 'DETAILED' && ids.length > 10 && (
          <p className="analysis-error">
            상세 기획은 최대 10건입니다. 선택 수를 줄이거나 빠른 정리를 이용하세요.
          </p>
        )}
      </div>
    </dialog>
  );
}

export function AnalysisWorkspace({
  topicId,
  focusJobId,
  onCollectTab,
}: {
  topicId: string;
  focusJobId: string;
  onCollectTab: () => void;
}) {
  const [jobs, setJobs] = useState<Page<Job>>({ items: [], total: 0, page: 0, size: 10 }),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0);
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(true),
    [detail, setDetail] = useState<{ job: Job; index: number } | null>(null);
  const [pending, setPending] = useState(false);
  useEffect(() => {
    setPage(0);
    setDetail(null);
    setRevision((v) => v + 1);
  }, [focusJobId]);
  useEffect(() => {
    let current = true;
    setLoading(true);
    api<Page<Job>>(`/analysis/jobs?topicId=${topicId}&page=${page}`)
      .then((v) => {
        if (current) setJobs(v);
      })
      .catch((e) => {
        if (current) setError(e.message);
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [topicId, page, revision]);
  const hasRunning = jobs.items.some(running);
  useEffect(() => {
    if (!hasRunning) return;
    const timer = setInterval(() => setRevision((v) => v + 1), 2000);
    return () => clearInterval(timer);
  }, [hasRunning]);
  async function open(job: Job, index: number) {
    setPending(true);
    setError('');
    try {
      setDetail({ job: await api<Job>(`/analysis/jobs/${job.id}`), index });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  async function cancel(id: string) {
    setPending(true);
    setError('');
    try {
      await api(`/analysis/jobs/${id}/cancel`, 'POST');
      setRevision((v) => v + 1);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  if (detail) {
    const issue = detail.job.result!.issues[detail.index];
    const sourceById = new Map(detail.job.input!.sources.map((s) => [s.id, s]));
    return (
      <div className="analysis-detail">
        <button className="text-button analysis-back" onClick={() => setDetail(null)}>
          <ArrowLeft size={16} /> 분석 목록으로
        </button>
        <div className="analysis-detail-head">
          <span className="badge green">{issue.category}</span>
          <h2>{issue.title}</h2>
          <p>{issue.summary}</p>
          <div className="head-tags">
            {issue.tags.map((t, i) => (
              <span key={i}># {t}</span>
            ))}
          </div>
        </div>
        <DraftComposer
          key={`${detail.job.id}-${detail.index}`}
          analysisId={detail.job.id}
          issueIndex={detail.index}
        />
        <div className="analysis-detail-grid">
          <section className="settings-card">
            <h3>어떤 글로 풀어내면 좋을까요?</h3>
            <p>{issue.angle}</p>
            <dl>
              <dt>추천 카테고리</dt>
              <dd>{issue.category}</dd>
              <dt>이 카테고리를 추천하는 이유</dt>
              <dd>{issue.categoryReason}</dd>
              <dt>대상 독자</dt>
              <dd>{issue.audience}</dd>
            </dl>
          </section>
          <section className="settings-card">
            <h3>블로그 제목 제안</h3>
            <ol>
              {issue.suggestedTitles.map((t, i) => (
                <li key={i}>{t}</li>
              ))}
            </ol>
            <h3>핵심 내용</h3>
            <ul>
              {issue.keyPoints.map((p, i) => (
                <li key={i}>{p}</li>
              ))}
            </ul>
          </section>
          <section className="settings-card">
            <h3>추천 글 구성</h3>
            <ol className="analysis-outline">
              {issue.outline.map((p, i) => (
                <li key={i}>{p}</li>
              ))}
            </ol>
          </section>
          <section className="settings-card analysis-cautions">
            <h3>작성 전 확인할 사항</h3>
            <p className="hint">
              아래 내용은 확보한 발췌문을 기준으로 한 기획 제안입니다. 실제 글을 작성하기 전 원문을
              확인하세요.
            </p>
            <ul>
              {issue.uncertainties.map((p, i) => (
                <li key={i}>{p}</li>
              ))}
            </ul>
          </section>
        </div>
        <section className="analysis-evidence">
          <h3>이 이슈에 묶인 원본 자료 · {issue.sourceIds.length}건</h3>
          {issue.sourceIds.map((id) => {
            const s = sourceById.get(id);
            return s ? (
              <article key={id}>
                <a href={s.url} target="_blank" rel="noopener noreferrer">
                  {s.title}
                  <ArrowUpRight size={16} />
                </a>
                <small>
                  {s.sourceName} · {date(s.publishedAt)} · {s.truncated ? '발췌문 일부' : '저장된 발췌문'}
                </small>
                <p>{s.excerpt || '설명이 제공되지 않았습니다.'}</p>
              </article>
            ) : null;
          })}
        </section>
        <div className="analysis-footnote">
          분석 시점 {date(detail.job.createdAt)} · {detail.job.mode === 'QUICK' ? '빠른 정리' : detail.job.mode === 'FULL' ? '원문 기획' : '상세 기획'}
          {detail.job.direction && <p>디렉션: {detail.job.direction}</p>}
        </div>
      </div>
    );
  }
  return (
    <div className="analysis-workspace">
      <div className="section-heading">
        <div>
          <h2>분석된 소재</h2>
          <p>선택한 자료를 이슈별로 나눈 한국어 요약과 블로그 기획입니다.</p>
        </div>
        <div className="collection-actions">
          <button className="button" onClick={onCollectTab}>
            <PlusLabel /> 자료 선택하기
          </button>
          <button
            className="icon-button"
            aria-label="분석 결과 새로고침"
            onClick={() => setRevision((v) => v + 1)}
          >
            <RefreshCw size={17} className={loading ? 'spin' : ''} />
          </button>
        </div>
      </div>
      {error && (
        <p className="analysis-error" role="alert">
          {error}
        </p>
      )}
      {!jobs.items.length ? (
        <div className="empty-state">
          <Sparkles size={30} />
          <h3>{loading ? '분석 목록을 불러오는 중' : '아직 분석한 소재가 없습니다'}</h3>
          <p>수집 결과에서 기간을 정하고 자료를 선택한 뒤 AI 분석을 시작하세요.</p>
          <button className="button primary" onClick={onCollectTab}>
            수집 자료 선택
          </button>
        </div>
      ) : (
        jobs.items.map((job) => (
          <section className={`analysis-job ${job.id === focusJobId ? 'focused' : ''}`} key={job.id}>
            <div className="analysis-job-head">
              <div>
                <span className={`status ${job.status.toLowerCase()}`}>
                  {names[job.status] || job.status}
                </span>
                <strong>{job.articleCount}건 분석</strong>
                <span className="muted">
                  {job.mode === 'QUICK' ? '빠른 정리' : job.mode === 'FULL' ? '원문 기획' : '상세 기획'} · {date(job.createdAt)}
                </span>
              </div>
              {running(job) && (
                <button className="button danger" disabled={pending} onClick={() => void cancel(job.id)}>
                  분석 중단
                </button>
              )}
            </div>
            <p className="analysis-job-message">
              {job.message || '선택한 자료만 전달할 준비를 하고 있습니다.'}
            </p>
            {running(job) && (
              <div className="analysis-progress" role="status">
                <RefreshCw size={17} className="spin" /> 구독 Codex가 분석 중입니다. 이 화면을 나가도 서버에서
                계속 진행됩니다.
              </div>
            )}
            {job.status === 'SUCCESS' && job.result && (
              <>
                <div className="issue-grid">
                  {job.result.issues.map((issue, index) => (
                    <button
                      className="issue-card"
                      key={index}
                      disabled={pending}
                      onClick={() => void open(job, index)}
                    >
                      <div>
                        <span className="badge green">{issue.category}</span>
                        <span>
                          <Layers3 size={13} />
                          {issue.sourceIds.length}개 출처
                        </span>
                      </div>
                      <h3>{issue.title}</h3>
                      <p>{issue.summary}</p>
                      <footer>
                        <span>
                          {issue.tags
                            .slice(0, 3)
                            .map((t) => '#' + t)
                            .join(' ')}
                        </span>
                        <strong>
                          블로그 기획 보기 <ArrowUpRight size={15} />
                        </strong>
                      </footer>
                    </button>
                  ))}
                </div>
                {job.result.excluded.length > 0 && (
                  <details className="analysis-excluded">
                    <summary>소재에서 제외한 자료 {job.result.excluded.length}건 · 이유 보기</summary>
                    {job.result.excluded.map((e) => (
                      <p key={e.sourceId}>
                        {e.reason}
                        <small>
                          {job.input?.sources.find((s) => s.id === e.sourceId)?.title || e.sourceId}
                        </small>
                      </p>
                    ))}
                  </details>
                )}
              </>
            )}
            {!running(job) && job.status !== 'SUCCESS' && (
              <p className="hint">
                자동 재시도하지 않았습니다. 수집 결과에서 원하는 자료를 다시 선택하여 실행할 수 있습니다.
              </p>
            )}
            <div className="analysis-footnote">
              전달 입력 {job.inputChars.toLocaleString()}자
              {job.inputTokens != null && <> · 실제 입력 {job.inputTokens.toLocaleString()}토큰</>}
              {job.cachedTokens != null && <> (캐시 {job.cachedTokens.toLocaleString()})</>}
              {job.outputTokens != null && <> · 출력 {job.outputTokens.toLocaleString()}토큰</>}
            </div>
          </section>
        ))
      )}
      <div className="pagination">
        <span>총 {jobs.total}회 분석</span>
        <div>
          <button className="button" disabled={page === 0 || loading} onClick={() => setPage((p) => p - 1)}>
            이전
          </button>
          <span>{page + 1}</span>
          <button
            className="button"
            disabled={(page + 1) * 10 >= jobs.total || loading}
            onClick={() => setPage((p) => p + 1)}
          >
            다음
          </button>
        </div>
      </div>
    </div>
  );
}
function PlusLabel() {
  return <FileText size={16} />;
}
