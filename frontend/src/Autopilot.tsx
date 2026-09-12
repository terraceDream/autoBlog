import { useEffect, useState } from 'react';
import { api, date } from './api';
import { DraftComposer } from './DraftComposer';
import blogConfig from './blog-config.json';
type Item = {
  id: string;
  issueIndex: number;
  title: string;
  category: string;
  draftId?: string;
  draftStatus?: string;
  draftMessage?: string;
  remoteUrl?: string;
};
type Run = {
  id: string;
  status: string;
  stage: string;
  message: string;
  analysisId?: string;
  blogUrl: string;
  createdAt: string;
  items: Item[];
};
type Board = { active: boolean; runs: Run[] };
const stages: Record<string, string> = {
  COLLECT: '수집',
  TRIAGE: '한글 요약·등급 분류',
  ANALYZE: '상위 후보 원문 분석',
  WRITE: '글 작성',
  SAVE: '티스토리 비공개 저장',
  DONE: '완료',
};
const statuses: Record<string, string> = {
  WRITING: '작성 중',
  READY: '작성 완료',
  SENDING: '비공개 저장 중',
  SAVED_PRIVATE: '비공개 저장 완료',
  EDITOR_READY: 'Chrome 확인 필요',
  UNKNOWN: '저장 여부 확인 필요',
  FAILED: '작성 실패',
};
export function Autopilot({ topicId, onActive }: { topicId: string; onActive: (active: boolean) => void }) {
  const [board, setBoard] = useState<Board | null>(null),
    [url, setUrl] = useState(blogConfig.defaultTistoryUrl),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [review, setReview] = useState<Item | null>(null);
  useEffect(() => {
    let live = true;
    const load = () =>
      api<Board>(`/topics/${topicId}/autopilot`)
        .then((b) => {
          if (live) {
            setBoard(b);
            onActive(b.active);
          }
        })
        .catch((e) => {
          if (live) setError(e.message);
        });
    void load();
    const timer = setInterval(() => void load(), 3000);
    return () => {
      live = false;
      clearInterval(timer);
      onActive(false);
    };
  }, [topicId, onActive]);
  async function start(resume?: string) {
    setBusy(true);
    setError('');
    try {
      await api(
        `/topics/${topicId}/autopilot${resume ? `/${resume}/resume` : ''}`,
        'POST',
        resume ? undefined : { blogUrl: url },
      );
      setBoard(await api<Board>(`/topics/${topicId}/autopilot`));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const run = board?.runs[0],
    running = busy || board?.active;
  return (
    <section className="autopilot-panel">
      <div>
        <span className="eyebrow">한 번에 비공개 발행까지</span>
        <h2>상위 소재 최대 5개, 검토만 남도록.</h2>
        <p>수집 → 1차 분류(최대 30건) → 원문 분석 → 카테고리별 글 작성 → 티스토리 비공개 저장</p>
      </div>
      <label>
        저장할 티스토리 블로그
        <input
          aria-label="자동 비공개 저장 블로그"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          disabled={!!running || run?.status === 'PAUSED'}
        />
      </label>
      <button
        className="button primary"
        disabled={!!running}
        onClick={() => void start(run?.status === 'PAUSED' ? run.id : undefined)}
      >
        {running
          ? '자동 작성 진행 중…'
          : run?.status === 'PAUSED'
            ? '멈춘 지점부터 이어서 실행'
            : '수집부터 TOP 5 비공개 저장'}
      </button>
      <small>
        AI 사용량이 소모됩니다. 근거가 충분한 추천만 작성하므로 5개보다 적을 수 있습니다. 로그인된 Chrome과
        Issue Desk 확장 연결을 켜 두세요. 공개 전환은 직접 검토한 후 진행합니다.
      </small>
      {error && (
        <p className="analysis-error" role="alert">
          {error}
        </p>
      )}
      {run && (
        <div className="autopilot-progress" role="status">
          <strong>
            {run.status === 'PAUSED' ? '확인 후 이어서 실행' : stages[run.stage] || run.stage} ·{' '}
            {run.items.filter((i) => i.draftStatus === 'SAVED_PRIVATE').length}/{run.items.length || 5}개 저장
          </strong>
          <p>{run.message}</p>
          <small>
            {date(run.createdAt)} · {run.blogUrl}
          </small>
        </div>
      )}
      {run && (
        <ol className="autopilot-items">
          {run.items.map((item) => (
            <li key={item.id}>
              <div>
                <strong>{item.title}</strong>
                <p>
                  {item.category} · {statuses[item.draftStatus || ''] || '작성 대기'}
                </p>
                {item.draftMessage && <small>{item.draftMessage}</small>}
              </div>
              {item.remoteUrl && (
                <a href={item.remoteUrl} target="_blank" rel="noreferrer">
                  티스토리 확인
                </a>
              )}
              {item.draftId && (
                <button className="button" disabled={!!running} onClick={() => setReview(item)}>
                  초안·저장 상태 확인
                </button>
              )}
            </li>
          ))}
        </ol>
      )}
      {review && run?.analysisId && (
        <div>
          <button className="button" onClick={() => setReview(null)}>
            초안 확인 닫기
          </button>
          <DraftComposer
            key={review.id}
            analysisId={run.analysisId}
            issueIndex={review.issueIndex}
            generationBlocked={!!running}
          />
        </div>
      )}
      {!!board && board.runs.length > 1 && (
        <details>
          <summary>이전 자동 실행 {board.runs.length - 1}건</summary>
          {board.runs.slice(1).map((r) => (
            <p key={r.id}>
              {date(r.createdAt)} · {r.message}{' '}
              <a href={r.blogUrl + '/manage/posts'} target="_blank" rel="noreferrer">
                글 관리
              </a>
            </p>
          ))}
        </details>
      )}
    </section>
  );
}
