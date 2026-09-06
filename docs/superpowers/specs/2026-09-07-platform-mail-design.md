# 플랫폼 메일 — 설치 옵션 메일 서버 + org-service 중앙 발송 + 관리 화면 (2026-09-07)

사용자: "메일 서버 따로 구축하자. 설치할 때 옵션으로, 어드민 페이지에 설정 옵션. 사내망에서도 동작해야. 많은 케이스를 지원하는 게 좋지."

## 0. 실측(현재)
- 메일 발송 구현이 **셋**(org 초대 `InvitationMailer` — 트랜잭션 안 동기 발송·`PLATFORM_MAIL_*` / wiki `EmailNotifier` — afterCommit + `wiki-mail` 스레드·`WIKI_MAIL_*` / alm `EmailNotifier` — 같은 구조·`ALM_MAIL_*`). auth-server는 메일 없음. env 접두사·미설정 처리·수신자 정책이 제각각이고 `.env`에는 메일 키가 하나도 없어 지금은 전부 꺼져 있다.
- infra에 메일 컨테이너 없음, compose `profiles:` 사용 0건(도입하면 새 메커니즘 — 이 문서가 그 규칙을 정한다).
- org-service Flyway 최신 V7. 내부 API 패턴 `/internal/org/**` + `InternalTokenFilter`(빈 아님, SecurityConfig 체인에 `new`로 삽입). 외부 호출 클라이언트 모양은 `KeycloakAdminClient`(JDK HttpClient, 예외 대신 Result enum).

## 1. 구조

```
[설치 옵션 MAIL_MODE]           [org-service = 플랫폼 메일 허브]              [소비자]
 none     컨테이너 없음           mail_setting(1행, 관리 화면이 편집)          wiki  ─┐
 external 컨테이너 없음, 외부/사내 SMTP  mail_outbox(큐·로그·재시도)              alm   ─┼→ POST /internal/org/mail
 relay    Postfix 발송 전용(릴레이 가능) JavaMailSenderImpl(설정 바뀌면 재조립)   org 초대 ─┘
 full     docker-mailserver(받은편지함)  /api/org/settings/mail (전역 관리자)
 dev      Mailpit(캡처)                 /internal/org/mail (X-Internal-Token)
```

- **발송은 org-service 한 곳**. 위키·ALM은 JavaMail을 버리고 내부 API로 넘긴다(수신자 결정·커밋 뒤 배치는 각 서비스가 지금처럼). 설정·자격증명·재시도·발송 로그가 한 곳에 모인다.
- **MAIL_MODE는 설치 시점 인프라 선택**(어떤 컨테이너를 띄우고 어떤 초기값을 심을지). **운영 중 값은 관리 화면**이 정본이다(env는 최초 1회 시드 + 잠금 옵션).

## 2. 설치 옵션 (infra-settings)

| MAIL_MODE | 컨테이너(compose profile) | 초기 시드(org mail_setting) | 용도 |
|---|---|---|---|
| `none`(기본) | 없음 | enabled=false | 메일 없이 운영(초대는 링크 복사) |
| `external` | 없음 | host/port/user/pw/tls = `.env`의 `MAIL_SMTP_*` | 사내 SMTP·Gmail·SES 등 기존 서버 사용 |
| `relay` | `mail-relay`(profile `mail-relay`) — Postfix 발송 전용(`boky/postfix`), 내부 587/25 | host=mail-relay port=25 tls=NONE 인증 없음 | 앱은 관문 하나만 봄. `MAIL_RELAY_HOST`가 비면 직접 배달(아웃바운드 25 필요, SPF), 채우면 사내/외부 SMTP로 릴레이(`MAIL_RELAY_USERNAME/PASSWORD`). `MAIL_DOMAIN`으로 `ALLOWED_SENDER_DOMAINS` |
| `full` | `mail`(profile `mail-full`) — `docker-mailserver`, 호스트 포트 25/465/587/993, 볼륨 data/state/logs/config | host=mail port=587 tls=STARTTLS user=`MAIL_ACCOUNT` | 완전 폐쇄망: 우리 서버가 MX. `scripts/mail-setup.ps1`이 계정 생성·DKIM 키 생성·필요 DNS(MX/SPF/DKIM/DMARC) 출력. IMAP 993으로 사용자가 읽음 |
| (dev) | `mailpit`(profile `mail-dev`) UI 8025·SMTP 1025 | host=mailpit port=1025 | 캡처만, 실제 발송 없음 |

- compose 규칙: 옵션 컴포넌트는 `profiles:`로 켠다(이 리포 첫 사용 — README "설치 옵션" 절에 규칙 명시). deploy.yml은 앱 서비스만 다루므로 메일 컨테이너는 `scripts/mail-up.ps1 -Mode relay|full|dev`(compose `--profile` + `up -d`)로 띄운다. `smoke-stack.ps1`: MAIL_MODE별 정합성(모드에 맞는 서비스 정의·env·org 시드 변수).
- `.env.example`: `MAIL_MODE`, `MAIL_DOMAIN`, `MAIL_FROM_ADDRESS`, `MAIL_FROM_NAME`, `MAIL_SMTP_HOST/PORT/USERNAME/PASSWORD/TLS`(external), `MAIL_RELAY_HOST/PORT/USERNAME/PASSWORD`(relay), `MAIL_ACCOUNT/MAIL_ACCOUNT_PASSWORD`(full), `ORG_SETTINGS_ENC_KEY`(비밀번호 암호화 키, 32바이트 hex — 없으면 org가 비밀번호 저장을 거부하고 400). 기존 `PLATFORM_MAIL_*`·`WIKI_MAIL_*`·`ALM_MAIL_*`는 제거(호환 불필요 — 지금 어디에도 설정돼 있지 않음).
- org-service compose env: `MAIL_MODE`, `MAIL_SEED_*`(위 시드값), `ORG_SETTINGS_ENC_KEY`. wiki/alm compose env: `ORG_INTERNAL_URI: http://org-service:9130`, `ORG_INTERNAL_TOKEN`(이미 auth-server가 씀 — 같은 값).

## 3. org-service

**V8** `mail_setting`(id=1 고정): `enabled bool`, `host`, `port int`, `username`, `password_enc`(AES-GCM, `ORG_SETTINGS_ENC_KEY`), `tls varchar(16)`(NONE|STARTTLS|SSL), `from_address`, `from_name`, `updated_by bigint null`, `updated_at`. 앱 기동 시 행이 없으면 `MAIL_SEED_*`로 시드(모드 none이면 enabled=false 빈 행).
`mail_outbox`: `id`, `to_address`, `subject`, `body_text`, `body_html null`, `source varchar(32)`(wiki|alm|org|test), `status`(PENDING|SENT|FAILED), `attempts int`, `last_error text null`, `next_attempt_at`, `created_at`, `sent_at null`. 인덱스 (status, next_attempt_at), (created_at). 30일 지난 SENT/FAILED는 일 1회 정리.

**발송기** `mail/MailSender`(JavaMailSenderImpl을 설정 버전 키로 재조립, 설정 PUT 시 무효화) + `mail/MailOutboxWorker`(5초 폴링, 배치 20, 지수 백오프 최대 5회 → FAILED, 단일 인스턴스 가정 — 행 잠금 `FOR UPDATE SKIP LOCKED`). enabled=false면 outbox에 넣지 않고 `disabled` 응답.

**API**
| 메서드 | 경로 | 권한 | 본문/응답 |
|---|---|---|---|
| GET | `/api/org/settings/mail` | 전역 관리자 | `{enabled, mode, host, port, username, passwordSet, tls, fromAddress, fromName, updatedAt, updatedBy}` — 비밀번호는 절대 안 돌려줌 |
| PUT | `/api/org/settings/mail` | 전역 관리자 | 같은 필드 + `password?`(생략=유지, ""=삭제). 검증: enabled면 host·port·fromAddress 필수. 저장 뒤 sender 무효화. 감사: updated_by |
| POST | `/api/org/settings/mail/test` | 전역 관리자 | `{to?}`(기본 = 요청자 이메일) → 동기 발송, `{ok, error?}`(SMTP 오류 문구 그대로) |
| GET | `/api/org/settings/mail/log?status&page&size` | 전역 관리자 | outbox 페이지 `{items:[{id,to,subject,source,status,attempts,lastError,createdAt,sentAt}], total}` |
| POST | `/api/org/settings/mail/log/{id}/retry` | 전역 관리자 | FAILED → PENDING |
| POST | `/internal/org/mail` | X-Internal-Token | `{to: string[], subject, text, html?, source}` → 202 `{accepted, disabled}`; to 최대 100, 빈 주소 제거 |
| GET | `/internal/org/mail/status` | X-Internal-Token | `{enabled}` — 소비자가 "메일 켜짐" UI 표시용(60초 캐시) |
- 초대 메일(`InvitationMailer`)은 outbox로(더 이상 트랜잭션 안 동기 발송 아님). `mailSent`는 "큐에 넣었다"로 의미 유지(enabled일 때 true).
- 오류 계약 `{"error"}`, 전역 관리자 판정은 `PermissionFacade.requireGlobalAdmin()`.

## 4. 소비자(wiki·alm)
- `OrgMailClient`(JDK HttpClient 또는 RestClient, `ORG_INTERNAL_URI`+`ORG_INTERNAL_TOKEN`, 연결 2초/읽기 5초, 실패는 warn + false). `EmailNotifier`는 JavaMailSender·`spring.mail.*`·`*_MAIL_HOST/PORT/USERNAME/PASSWORD/FROM`을 버리고 이 클라이언트로 보낸다. **수신자 결정(GetMembers·차단 상태)·커밋 뒤 배치·다이제스트 스케줄은 그대로**. `configured()`는 `/internal/org/mail/status` 60초 캐시.
- alm-backend는 ALM 세션(msa-template-06) 몫 — 같은 계약.

## 5. 관리 화면 (`@chanho/org-admin` 0.2.0)
"메일 설정" 화면(전역 관리자 메뉴): 상단 모드 배지(`mode`) + 안내(모드별 한 줄), 폼(사용 여부 Switch, 호스트, 포트, 사용자, 비밀번호(저장됨 표시·변경 입력), TLS Select, 보내는 주소·이름), [저장] [테스트 발송](결과 토스트에 SMTP 오류 문구 그대로), 아래 "발송 로그" 표(상태 필터, 받는 주소·제목·출처·상태·시도·오류·시각, 실패 행 [다시 보내기]). DS만, role 쿼리 테스트. wiki-front·alm-front 범프.

## 6. 단계
- M1 org-service(V8·발송기·outbox·API·초대 전환) ∥ M2 infra(compose 프로필 4종·env·mail-up/mail-setup 스크립트·smoke·README) ∥ M3 org-admin 화면.
- M4 wiki 소비 전환(+ compose env) · alm은 06 세션 · M5 wiki-front/alm-front 범프 · 실스택 검증(Mailpit 모드로 초대·위키 알림·테스트 발송 캡처 확인).
