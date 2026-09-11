# 개인용 서버 운영

## 접속과 구성
- 주소: https://54.116.23.226 (ID `owner`, 초기 비밀번호는 로컬 `.tools/deploy/access.txt`)
- Festival과 동일한 Lightsail 서버. Nginx의 IP 가상 호스트, `autoblog` 컨테이너, `fstv` DB의 `autoblog` 스키마·계정으로 분리한다.
- 백엔드는 `127.0.0.1:8081`에만 노출한다. 인터넷 요청은 HTTPS와 Nginx 비밀번호 인증을 거친다. Chrome 작업 수신·결과 POST 두 경로만 별도의 무작위 연결 토큰으로 인증한다.
- 서버 `/home/ubuntu/autoblog/server.env`에 DB 접속 정보를, `state/codex`에 GPT 인증을 보관한다. 저장소와 Actions에는 넣지 않는다.
- 컨테이너 메모리 상한 900MB, Java heap 512MB, DB pool 4개. 실제 사용량을 보고 조정한다.

## GPT와 Chrome
- 서버에 Codex CLI 0.153.4를 설치하고 ChatGPT 구독 인증을 사용한다. 인증 정보와 모델 설정은 재배포해도 유지되는 `state/codex` 볼륨에 둔다.
- 로그인 확인: `sudo docker exec autoblog codex login status`
- 갱신 필요 시: `sudo docker exec -it autoblog codex login --device-auth`. 표시된 주소와 코드를 사용자가 직접 확인한다.
- 구독 한도는 계정 전체에서 공유한다. 로그인 상태가 유지돼도 한도에 도달하면 분석은 중단된다. API 키로 자동 전환하지 않는다.
- 공식 문서는 원격 장치의 구독 로그인과 인증 캐시 이관을 지원하며, 자동화의 기본 인증으로는 API 키를 권장한다: https://learn.chatgpt.com/docs/auth
- 작업실의 Chrome 확장 다운로드는 해당 서버 주소와 연결 토큰을 포함한다. 압축을 풀고 기존 확장 디렉터리에 덮어쓴 뒤 Chrome 확장 관리에서 새로고침한다. 로그인 세션과 기존 탭은 유지한다.
- 수집·추천·초안 작성은 PC가 꺼져 있어도 서버에서 가능하다. **티스토리 저장은 로그인된 Chrome과 확장이 실행 중이어야 한다.** 휴대폰만으로 서버의 초안 관리는 가능하지만 PC Chrome이 꺼져 있으면 게시 작업을 수행하지 못한다.

## CI/CD와 복구
- `main` push: 백엔드 테스트·패키징, 프런트 빌드 → 아티팩트 업로드 → production 배포.
- Actions secrets: LIGHTSAIL_HOST, LIGHTSAIL_USERNAME, LIGHTSAIL_SSH_KEY, LIGHTSAIL_KNOWN_HOSTS. 서버 키를 검증하며 접속한다.
- `deploy/release.sh`가 autoBlog만 교체한다. 헬스체크 성공 후 정적 파일 링크를 전환한다. 실패하면 이전 컨테이너 이미지로 복구한다. DB 마이그레이션은 자동 역변환하지 않으므로 파괴적 변경은 별도 복구 계획이 필요하다.
- 배포 전 및 매일 한국시간 03:30 스키마 백업, 14일 보관. `/home/ubuntu/autoblog/backups`에서 확인한다. 동일 서버 백업이므로 서버 자체 유실에 대비한 외부 백업은 별도다.
- IP 인증서는 6일가량 유효하다. 6시간마다 별도 Certbot 컨테이너로 갱신 확인 후 Nginx를 reload한다. `/var/log/autoblog-cert-renew.log`와 `/etc/cron.d/autoblog`에서 확인한다.
- Festival `deploy_remote.sh`의 HTTPS `default_server`를 제거해 IP 인증서 선택을 autoBlog가 담당하게 했다. Festival의 도메인 인증서와 라우팅은 유지한다.
- 이미지·과거 정적 릴리스는 자동 삭제하지 않는다. 디스크를 확인하고 필요한 복구 버전을 남긴 뒤 정리한다.

## 데이터 이관
- 로컬 수집·AI 작업이 없는 것을 확인하고 서버를 중지한 뒤 H2 파일 백업을 생성했다. 로컬 서버를 다시 켜면 데이터가 갈라지므로 이후 운영은 원격 서버를 사용한다.
- `deploy/ExportH2.java`는 정지된 H2 백업을 읽어 트랜잭션 SQL을 생성한다. 대상 테이블이 하나라도 비어 있지 않으면 중단하며 테이블별 건수도 검증한다. Flyway 이력은 PostgreSQL 서버에서 별도로 생성한다.
- 이관 시점: topics 1, sources 10, articles 297, topic_articles 297, runs 5, run_sources 36, analysis_jobs 5, blog_drafts 14, editorial_settings 1, editorial_runs 3.
- 원본 H2 및 `.tools/deploy/issuedesk-backup.mv.db`를 보존한다. 이후 서버 QA로 생성한 분석이나 운영 데이터는 위 건수에서 늘어날 수 있다.

## 비밀번호 변경
서버에서 `sudo htpasswd /etc/nginx/autoblog.htpasswd owner`로 변경한다(apache2-utils 필요). 비밀번호를 명령 인자로 전달하거나 Git에 기록하지 않는다. Chrome 연결 토큰은 로그인 비밀번호와 별도이며, 확장 ZIP 역시 공유하지 않는다.
