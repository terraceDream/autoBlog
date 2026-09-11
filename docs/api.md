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

초안 수정에는 선택적인 `images` 배열(최대 3개)을 추가할 수 있습니다. 각 항목은
`{url, sourceUrl, credit, license, licenseUrl, caption, afterParagraph, rightsConfirmed}`입니다.
URL 3개는 공개 HTTPS 주소, `rightsConfirmed`는 true여야 합니다. 서버는 사용 허가의 진위를 자동 판정하지 않습니다.
`afterParagraph`는 0~100이며 0은 맨 위, 문단 수를 넘으면 마지막 문단 뒤에 삽입합니다.
조회 응답의 `previewHtml`은 저장된 본문과 이미지를 합친 HTML입니다. 수정 저장 전 편집값은 반영되지 않습니다.
모델 출력과 수동 본문 HTML의 img는 제거하며, 별도 이미지 배열만 렌더링합니다. 기존 images 없는 초안도 지원합니다.

새 초안 생성은 Commons 이미지 검색 후 모델의 `imageSelections`를 검증해 `result.images`에 저장합니다. 모델은 후보 ID, 한국어 caption, afterParagraph만 선택하고 URL·라이선스는 서버 후보에서 가져옵니다. 검색 상태와 후보는 `input.imageCatalog`에 보관됩니다. `previewHtml`과 전송 HTML에는 이미지 및 인라인 표 스타일이 포함됩니다.
# 추천 작업실 API

- `GET /api/topics/{id}/editorial`: 독자 설정, 수집·AI 상태, 최근 추천 실행, 후보 근거, 출처별 수집 결과.
- `PUT /api/topics/{id}/editorial/settings`: `{audience, automatic}`. 자동 설정은 수집 전 검색 출처 연결과 수집 후 추천을 제어한다.
- `POST /api/topics/{id}/editorial/collect`: 출처 자동 연결부터 수집·추천까지 실행. 중복 실행은 거절한다.
- `POST /api/topics/{id}/editorial/recommend`: 현재 수집 자료로 재선별. 원문 전체를 확보하고 `FULL` 분석 모드로 기획한다.
- 기존 `PATCH /api/articles/status`의 `HIDDEN`은 추천 후보에서도 제외한다. 수집 결과에서 상태를 변경하면 복원된다.

`FULL`은 최대 10건, 총 입력 120,000자다. 원문을 절단하지 않고 입력 상한을 넘으면 후보 수를 줄인다. 관심 신호는 `signals`에 제공자·관측 시점·추천/댓글 수·토론 링크로 보존한다. 신호가 없으면 `{}`이며 0회 반응이라는 뜻이 아니다.
