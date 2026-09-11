# 로컬 설정 및 구조 확인 (2026-09-08)

## 실행 환경

- 저장소: https://github.com/terraceDream/autoBlog.git
- 작업 폴더: `C:\project\workspace\autoBlog`
- 화면: http://127.0.0.1:5174 (5173은 기존 프로그램이 사용 중)
- API: http://127.0.0.1:8080/api/health
- Java: 프로젝트 `.tools/java`에 Temurin JDK 17 설치, 배포 체크섬 검증 완료
- Node.js: 기존 22.15.0 사용. frontend/analysis-runtime 각각 `npm ci` 완료
- DB: 기본 파일 H2, `backend/data/issuedesk.mv.db`; Flyway V1~V3 적용
- `.env`의 `JAVA_HOME`, `FRONTEND_PORT`를 시작 스크립트에서 사용. 전역 Java 설정은 변경하지 않음

실행: `./scripts/start.ps1 -SkipInstall`

종료: `./scripts/stop.ps1`

## 처리 흐름과 코드

1. `frontend/src/App.tsx`: 분야, 출처, 수집 결과, 실행 이력 관리.
2. `ApiController` / `Store`: API 입력과 JDBC 저장. 분야와 원본 자료는 연결 테이블로 분리.
3. `collector/*`: RSS/Atom, YouTube, 네이버 뉴스/블로그 수집. `SafeHttp`가 외부 요청을 제한.
4. `CollectionService`: 수동/cron 실행, 순차 큐, 키워드 필터, 정규화 URL 해시로 중복 방지. 출처별 실패 분리.
5. `AnalysisService` / `CodexAnalysisRunner`: 선택 자료를 로컬 Codex CLI로 분석, 소재/글 기획 생성. 동일 성공 입력 재사용.
6. `OriginalReader` / `DraftService`: 선택 원문 최대 5개 참조, 한국어 HTML 초안 생성/정리/수정.
7. `TistoryPublisher` → `analysis-runtime/tistory.mjs`: Playwright와 Edge로 티스토리 비공개 저장 및 결과 확인.

자동 예약은 수집에만 적용된다. 분석, 초안 작성, 비공개 전송은 사용자 실행 단계이며 공개 발행은 티스토리에서 직접 진행한다.
서버가 실행되어 있어야 예약 수집이 동작한다. 기본 구성은 인증 없는 로컬 단일 사용자용이다.

## 연결 준비 상태

- RSS: 준비됨. 실제 사용할 피드와 분야 등록 필요.
- AI: `/api/analysis/status`에서 ChatGPT 구독 로그인 확인. 실제 추론 호출은 이번 설정 검증에서 수행하지 않음.
- YouTube: `.env`에 `YOUTUBE_API_KEY` 필요.
- 네이버: `.env`에 `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` 필요.
- 티스토리: Edge 설치 확인. 이 PC에서 계정 로그인과 실제 저장은 미검증.

## 검증 결과

- Maven: 19개 중 18개 통과, 외부 AI smoke 1개 기본 제외. 실패/오류 0.
- 프런트엔드: TypeScript 및 Vite production build 성공.
- 티스토리 모듈: Node 테스트 2개 통과.
- 서버 health UP, Vite API 프록시, 초기 화면 렌더링 확인.
- 포트 변경 후 Origin 검증: 로컬 5174 요청은 입력 검증까지 도달(400), 외부 origin은 차단(403).
- 실제 외부 수집, AI 글 생성, 티스토리 계정 저장까지의 종단 간 테스트는 수행하지 않음.

## 이번 수정

`JAVA_HOME`을 시작 스크립트가 우선 사용하도록 보완하고, `FRONTEND_PORT`를 화면과 백엔드 Origin 검증에 함께 적용했다.
README의 오래된 기능 범위와 실행 경로를 정정했다. 변경은 로컬 작업 트리에 있으며 커밋/푸시하지 않았다.

확장 시 우선 검토할 부분은 수집 이후 단계의 예약 연결, 공개 발행 정책, 실패 작업 재처리, 계정별 연결 관리다.
현재 URL 중복 제거는 동일 사건의 기사 묶음 판단과 별개이며, 티스토리 연동은 화면 변경에 영향을 받는다.
