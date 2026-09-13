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
  skipped?: boolean;
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
  logs: { status: string; stage: string; message: string; createdAt: string }[];
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
    [review, setReview] = useState<Item | null>(null),
    [viewedId, setViewedId] = useState<string | null>(null);
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
    setViewedId(null);
    setReview(null);
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
  async function recover(path: string) {
    setBusy(true);
    setError('');
    try {
      await api(`/topics/${topicId}/autopilot/${path}`, 'POST');
      setBoard(await api<Board>(`/topics/${topicId}/autopilot`));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const currentRun = board?.runs.find((r) => ['RUNNING', 'PAUSED'].includes(r.status)),
    run = currentRun || board?.runs.find((r) => r.id === viewedId),
    running = busy || board?.active;
  return (
    <section className="autopilot-panel">
      <div>
        <span className="eyebrow">한 번에 비공개 발행까지</span>
        <h2>새로운 자동 실행</h2>
        <p>수집 → 1차 분류(최대 30건) → 원문 분석 → 카테고리별 글 작성 → 티스토리 비공개 저장</p>
      </div>
      {!currentRun && (
        <p>
          진행 중인 실행이 없습니다. 아래 버튼은 새 수집부터 시작하며, 이전 실행 기록은 다시 실행하지
          않습니다.
        </p>
      )}
      <label>
        저장할 티스토리 블로그
        <input
          aria-label="자동 비공개 저장 블로그"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          disabled={!!running || currentRun?.status === 'PAUSED'}
        />
      </label>
      <button
        className="button primary"
        disabled={!!running}
        onClick={() => void start(currentRun?.status === 'PAUSED' ? currentRun.id : undefined)}
      >
        {running
          ? '자동 작성 진행 중…'
          : currentRun?.status === 'PAUSED'
            ? '현재 멈춘 실행 이어서 진행'
            : '최신 자료 새로 수집해서 실행'}
      </button>
      {currentRun?.status === 'PAUSED' && (
        <button
          className="button"
          disabled={!!running}
          onClick={() => void recover(`${currentRun!.id}/cancel`)}
        >
          이 실행 종료 · 새로 시작 가능
        </button>
      )}
      {currentRun?.status === 'PAUSED' && (
        <small>
          실행을 종료해도 작성한 초안과 티스토리 글은 삭제되지 않습니다. 같은 오류가 반복되면 해당 글만 건너뛸
          수도 있습니다.
        </small>
      )}
      <small>
        최근 7일 내 자료 중 이전에 작성한 원문을 제외하고 선택합니다. 발행일이 없으면 수집일을 사용합니다. AI
        사용량이 소모됩니다. 근거가 충분한 추천만 작성하므로 5개보다 적을 수 있습니다. 로그인된 Chrome과 Issue
        Desk 확장 연결을 켜 두세요. 공개 전환은 직접 검토한 후 진행합니다.
      </small>
      {error && (
        <p className="analysis-error" role="alert">
          {error}
        </p>
      )}
      {run && (
        <div className="autopilot-progress" role="status">
          <h3>{currentRun ? '현재 진행 중인 실행' : '이전 실행 기록 조회 (재실행 아님)'}</h3>
          <strong>
            {run.status === 'CANCELLED'
              ? '종료한 실행'
              : run.status === 'PAUSED'
                ? '확인 후 이어서 실행'
                : stages[run.stage] || run.stage}{' '}
            · {run.items.filter((i) => i.draftStatus === 'SAVED_PRIVATE').length}/{run.items.length || 5}개
            저장
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
                  {item.category} ·{' '}
                  {item.skipped ? '건너뜀 (기존 초안 보존)' : statuses[item.draftStatus || ''] || '작성 대기'}
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
              {run.status === 'PAUSED' &&
                !item.skipped &&
                !['SAVED_PRIVATE', 'WRITING', 'SENDING'].includes(item.draftStatus || '') && (
                  <button
                    className="button"
                    disabled={!!running}
                    onClick={() => void recover(`${run.id}/items/${item.id}/skip`)}
                  >
                    이 글 건너뛰기
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
      {run && (
        <details open={!!currentRun}>
          <summary>
            {currentRun ? '현재 실행 로그' : '선택한 이전 실행 로그'} · {date(run.createdAt)}
          </summary>
          <p>실행 ID: {run.id}</p>
          <ol>
            {run.logs?.map((log, i) => (
              <li key={i}>
                <time>{date(log.createdAt)}</time> · {stages[log.stage] || log.stage} · {log.message}
              </li>
            ))}
          </ol>
        </details>
      )}
      {!!board && (
        <details>
          <summary>
            이전 실행 기록 · {board.runs.filter((r) => !['RUNNING', 'PAUSED'].includes(r.status)).length}건
            (최근 30건)
          </summary>
          <p>
            과거 기록에는 당시 저장한 마지막 상태만 표시됩니다. 이번 업데이트 이후 실행부터 단계별 로그가
            쌓입니다.
          </p>
          {board.runs
            .filter((r) => !['RUNNING', 'PAUSED'].includes(r.status))
            .map((r) => (
              <div key={r.id}>
                <p>
                  {date(r.createdAt)} ·{' '}
                  {({ COMPLETE: '완료', PARTIAL: '일부 완료', CANCELLED: '종료' } as Record<string, string>)[
                    r.status
                  ] || r.status}{' '}
                  · {r.items.filter((i) => i.draftStatus === 'SAVED_PRIVATE').length}개 저장
                </p>
                <p>{r.message}</p>
                <button
                  className="button"
                  disabled={!!currentRun}
                  onClick={() => {
                    setViewedId(r.id);
                    setReview(null);
                  }}
                >
                  이 실행의 로그·글 목록 보기
                </button>
              </div>
            ))}
        </details>
      )}
    </section>
  );
}
