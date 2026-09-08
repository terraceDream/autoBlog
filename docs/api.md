# Issue Desk API

기본 URL: `http://127.0.0.1:8080/api`. JSON 요청/응답. 오류는 HTTP 상태와 `{ "message": "..." }`를 반환합니다.
로컬 단일 사용자 버전입니다. 프런트는 Vite `/api` 프록시를 사용합니다.

| Method | Path | 기능 |
|---|---|---|
| GET | `/health` | 서버 상태 |
| GET | `/modules` | 모듈 목록 및 인증 준비 상태 |
| GET / POST | `/topics` | 분야 목록 / 생성 |
| PUT / DELETE | `/topics/{id}` | 분야 수정 / 삭제 |
| GET / POST | `/topics/{id}/sources` | 출처 목록 / 생성 |
| PUT / DELETE | `/topics/{id}/sources/{sourceId}` | 출처 수정 / 삭제 |
| POST | `/topics/{id}/collect` | 수집 요청. 202와 `{runId}` 반환 |
| GET | `/topics/{id}/stats` | 분야별 상태 건수·출처 목록·실행 중 여부 |
| GET | `/articles?topicId=...` | 자료 목록 |
| PATCH | `/articles/status` | 분야별 자료 일괄 상태 변경 |
| GET | `/runs?topicId=...&page=0` | 실행 이력 및 출처별 결과 |

## 분야 생성/수정

```json
{
  "name": "AI 개발 도구",
  "description": "개발 도구와 업데이트",
  "instructions": "신규 출시와 사용 후기 중심",
  "keywords": ["AI", "코딩"],
  "exclusions": ["광고"],
  "tags": ["기술"],
  "language": "ko",
  "region": "KR",
  "active": true,
  "scheduleEnabled": false,
  "cron": "0 0 9 * * *",
  "timezone": "Asia/Seoul"
}
```

## 출처 생성/수정

```json
{
  "name": "기술 블로그",
  "type": "RSS",
  "media": "BLOG",
  "url": "https://example.com/feed.xml",
  "query": "",
  "channelId": "",
  "enabled": true
}
```

`type`: RSS / YOUTUBE / NAVER_NEWS / NAVER_BLOG. RSS 외 매체 분류는 서버가 모듈에 맞게 지정합니다.
RSS는 URL, 검색 모듈은 선택적인 검색어/YouTube 채널 ID를 사용합니다.
YouTube 채널 ID는 UC로 시작하는 24자리 값입니다.

## 자료 조회

`topicId` 필수. 선택: `q` (제목), `media` (NEWS/BLOG/VIDEO/OTHER), `source` (출처명),
`status` (UNREAD/READ/SAVED/HIDDEN), `from`/`to` (YYYY-MM-DD, 양쪽 날짜 포함),
`page` (0부터), `size` (1~100, 기본 20), `sort` (asc/desc, 게시일).

상태 미지정 시 HIDDEN 제외. 게시일 없는 자료는 날짜 필터 사용 시 제외되고, 정렬 시 마지막에 표시됩니다.
응답: `{ "items": [...], "total": 42, "page": 0, "size": 20 }`.

## 상태 일괄 변경

```json
{ "topicId": "분야 UUID", "ids": ["자료 UUID"], "status": "SAVED" }
```

한 번에 최대 100개. 일부 자료가 해당 분야에 없으면 404를 반환하고 전체 변경을 롤백합니다.
수집 실행 중인 분야의 중복 실행·삭제 및 출처 변경은 409를 반환합니다.

## 수집 실행

실행: QUEUED → RUNNING → SUCCESS / PARTIAL / FAILED.
출처 결과의 `added`는 해당 분야에 새로 연결된 자료 수이며, 전역 원본 신규 생성 수와 다를 수 있습니다.
`duplicates`는 이미 그 분야에 연결된 자료, `filtered`는 키워드 제외/유효하지 않은 항목입니다.
이력은 페이지당 20개입니다.

## 수동 AI 분석

- `GET /api/analysis/status`: 로컬 ChatGPT 로그인 확인 (추론 없음)
- `POST /api/analysis/preview`: `{topicId, articleIds, mode: QUICK|DETAILED, direction}` 범위·발췌·입력 분량·fingerprint·재사용 가능한 결과 확인 (추론 없음)
- `POST /api/analysis/jobs`: `{request: 위 요청, fingerprint}` 확인한 범위로 실행. 변경된 입력은 409, 기존 성공 결과는 재사용
- `GET /api/analysis/jobs?topicId=...&page=0`: 10개 단위 이력 및 소재 목록
- `GET /api/analysis/jobs/{id}`: 입력 스냅샷, 한국어 소재·블로그 기획·출처, 실제 토큰 사용량
- `POST /api/analysis/jobs/{id}/cancel`: 대기/진행 중 작업 중단

한 번에 QUICK 최대20건/DETAILED 최대10건. 분야에 속하지 않은 자료와 중복 ID는 거절합니다. 결과의 출처 ID는 입력과 대조하며 모든 자료가 소재 또는 제외 사유에 한 번씩 포함되어야 저장됩니다. 서버 재시작으로 중단된 작업은 실패로 처리하며 재실행하지 않습니다.

## 블로그 초안과 티스토리

- `POST /api/drafts`: `{analysisId, issueIndex, direction}` 원문 참조와 글 작성 시작
- `GET /api/drafts?analysisId=...&issueIndex=0`: 소재별 작성 이력
- `GET /api/drafts/{id}`: 입력 원문 스냅샷·작성 상태·결과
- `PUT /api/drafts/{id}`: `{title, html, category, tags, checks}` 초안 수정 (READY 상태만)
- `POST /api/drafts/{id}/tistory`: `{blogUrl: "https://name.tistory.com"}` 비공개 저장 시작

`WRITING → READY → SENDING → SAVED_PRIVATE`가 정상 흐름입니다. `UNKNOWN`/`EDITOR_READY`는 사용자가 열린 티스토리 화면에서 확인해야 합니다. 전송 중 재시작도 UNKNOWN으로 남기며 중복 전송을 차단합니다. 외부 글 공개 발행 API는 제공하지 않습니다.
