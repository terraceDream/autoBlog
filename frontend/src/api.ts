export type Topic = {
  id: string;
  name: string;
  description: string;
  instructions: string;
  keywords: string[];
  exclusions: string[];
  tags: string[];
  language: string;
  region: string;
  active: boolean;
  scheduleEnabled: boolean;
  cron: string;
  timezone: string;
  nextRun?: string;
  createdAt: string;
  updatedAt: string;
  articleCount: number;
  sourceCount: number;
};
export type TopicInput = Omit<
  Topic,
  'id' | 'createdAt' | 'updatedAt' | 'articleCount' | 'sourceCount' | 'nextRun'
>;
export type Source = {
  id: string;
  topicId: string;
  name: string;
  type: string;
  media: string;
  url: string;
  query: string;
  channelId: string;
  enabled: boolean;
  lastSuccess?: string;
};
export type SourceInput = Omit<Source, 'id' | 'topicId' | 'lastSuccess'>;
export type Article = {
  id: string;
  title: string;
  url: string;
  media: string;
  sourceName: string;
  author: string;
  excerpt: string;
  coverage: string;
  publishedAt?: string;
  collectedAt: string;
  status: string;
  matchedKeywords: string[];
};
export type Module = { type: string; name: string; description: string; ready: boolean; setup: string };
export type RunSource = {
  id: string;
  sourceName: string;
  sourceType: string;
  status: string;
  fetched: number;
  added: number;
  duplicates: number;
  filtered: number;
  message: string;
};
export type Run = {
  id: string;
  status: string;
  triggerType: string;
  startedAt: string;
  finishedAt?: string;
  message: string;
  sources: RunSource[];
};
export type Page<T> = { items: T[]; total: number; page: number; size: number };
export type Stats = {
  running: boolean;
  statuses: { status: string; count: number }[];
  sources: { sourceName: string }[];
};
export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/api' + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  }).catch(() => {
    throw new Error('로컬 서버에 연결할 수 없습니다. Issue Desk 서버를 실행한 뒤 다시 시도해 주세요.');
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || error.detail || '요청에 실패했습니다. 서버 연결을 확인해 주세요.');
  }
  if (response.status === 204) return undefined as T;
  return response.json();
}
export const date = (s?: string) =>
  s
    ? new Intl.DateTimeFormat('ko-KR', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(new Date(s))
    : '날짜 미제공';
export const mediaNames: Record<string, string> = {
  NEWS: '기사',
  BLOG: '블로그',
  VIDEO: '유튜브',
  OTHER: '기타',
};
export const statusNames: Record<string, string> = {
  UNREAD: '미확인',
  READ: '읽음',
  SAVED: '보관',
  HIDDEN: '숨김',
  QUEUED: '대기 중',
  RUNNING: '수집 중',
  SUCCESS: '완료',
  FAILED: '실패',
  PARTIAL: '일부 실패',
};
