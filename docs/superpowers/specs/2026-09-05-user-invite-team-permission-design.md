# 사용자 추가·초대·그룹(팀) 권한 — 플랫폼 공통 설계 (2026-09-05)

사용자 지시: "사용자 추가 및 초대, 그룹 권한에 대한 설계와 개발". 결정(2026-09-05): 초대 없는 가입은 **차단**, 관리 화면은
**공용 패키지 하나**를 wiki·alm 양쪽에 마운트, SMTP가 없으면 **초대 링크 복사**로 시작. 확정 결정 유지: 로그인은 Keycloak 리다이렉트
(OIDC)+구글, 자체 로그인 폼·ROPC 없음. 분담: 코어(이 문서·org-service·auth-server·공용 화면·wiki 마운트)=이 세션,
아바타/프로필(`member_profile`, V7~)·ALM 소비=ALM 세션.

## 0. 실측(2026-09-05)과 문제

- 계정 원천 Keycloak(realm `sso-demo`, 구글 IdP, 실 역할 USER/ADMIN, 그룹 없음, SMTP 없음, `registrationAllowed: true`, Admin API 클라이언트 없음).
- id 사슬: Keycloak `sub`(UUID) → auth-server `users.id`(숫자, 첫 로그인 때 생성) → org-service `member.id`(첫 API 호출 때 JIT 미러).
  **따라서 로그인 전에는 그 사람의 id가 없어 팀·권한을 미리 줄 수 없다.** 초대는 **이메일**을 키로 해야 한다.
- org-service: `member(status ACTIVE|DEACTIVATED, kind HUMAN|AGENT)`, `team`, `team_member(LEAD|MEMBER)`, `grant_entry(USER|TEAM × GLOBAL|SPACE|PROJECT × VIEWER|EDITOR|ADMIN)`, `grant_audit`.
  전역 관리자 = `GLOBAL/ADMIN` grant(부트스트랩 `PLATFORM_BOOTSTRAP_ADMIN_ID`). ALM 백엔드는 Keycloak 실 역할 `ADMIN`으로 따로 판정 — **둘로 갈라짐**.
- 화면: 초대·사용자 관리 화면 없음. 팀 관리는 wiki `/admin/teams`뿐. 사용자 목록은 `GET /api/org/members` 전체 반환(검색·페이지 없음, DEACTIVATED·AGENT 포함).
- 역할 변경 API 없음(삭제 후 재생성), 마지막 관리자 강등 방지는 클라이언트 전용, `GET /api/org/me`에 전역 역할 없음.

## 1. 목표·범위

**목표.** 관리자가 화면에서 사람을 초대하고, 초대받은 사람이 구글 또는 비밀번호로 로그인하는 순간 미리 정한 팀·권한을 가진 활성 사용자가 되며,
초대 없이 들어온 계정은 승인 전까지 아무것도 못 한다. 팀이 권한의 기본 단위가 되고, 전역 관리자 판정은 org-service 하나로 통일된다.

**포함(U1~U4).** 초대 원장·수락·만료·철회·재발송, 미초대 로그인 PENDING 격리 + 승인, 사용자 상태 수명주기(ACTIVE/SUSPENDED/DEACTIVATED) + Keycloak 계정
비활성 연동, 팀 확장(리더 권한·"전체 구성원" 기본 팀), grant 역할 변경·마지막 관리자 보호, 멤버 검색·페이지·필터, `/me` 표준, 공용 관리 화면
(사용자·초대·팀·전역 역할·승인 대기), 초대 메일(선택) + 링크 복사.

**제외.** 자체 회원가입 폼(확정 결정), 조직 멀티테넌시, Keycloak 그룹 사용(팀 원장은 org-service), 아바타·프로필(ALM 세션 V7~), SCIM/LDAP 동기화,
비밀번호 정책(Keycloak 설정), COMMENT/RESTRICT 세분 action 확장(기존 wiki 전용 유지).

## 2. 흐름

```
관리자: 초대 생성(이메일·팀·권한 프리셋) ─→ invitation(PENDING, token) ─→ 메일 발송(설정 시) / 링크 복사
초대받은 사람: 링크 → auth-server /invite/{token} → (토큰 검증, login_hint=email) → Keycloak 로그인(구글 or 가입 화면)
              → auth-server users 행 생성 → 첫 org API 호출 → MemberMirrorFilter:
                    PENDING 초대(이메일 일치) 있음 → member ACTIVE + 팀·권한 적용 + invitation ACCEPTED
                    없음                         → member PENDING(격리) → "승인 대기" 화면 → 관리자 승인 → ACTIVE
```
- 토큰은 링크 검증·`login_hint`·수락 추적용이다. **매칭의 근거는 이메일**이므로 토큰 없이 그냥 로그인해도 초대가 소진된다(구글 계정으로 바로 들어오는 경우).
  같은 이메일에 PENDING 초대가 둘 이상이면 가장 최근 것만 유효(이전 것은 생성 시점에 EXPIRED 처리).
- 이메일 대조는 소문자·trim. Keycloak 구글 IdP는 `trustEmail: true`라 이메일을 신뢰한다. 비밀번호 가입은 Keycloak 가입 화면을 그대로 두되
  (`registrationAllowed: true` 유지 — 끄면 초대받은 비밀번호 사용자가 계정을 만들 길이 없다), 플랫폼 쪽에서 PENDING으로 격리하므로 "초대 없는 가입 차단"이 성립한다.
  ⚠️ Keycloak 이메일 검증(`verifyEmail`)은 SMTP가 생기면 켠다 — 그 전엔 비밀번호 가입자가 남의 이메일을 적을 수 있으므로 **초대 수락 시 이메일 일치 + 토큰 링크 경유를 요구**한다
  (토큰 없이 비밀번호 가입한 경우는 초대가 있어도 PENDING → 관리자 승인). 구글 로그인은 토큰 없이도 수락.

## 3. org-service

### 3.1 스키마 (V4~V6; V7~은 ALM 세션)
```
V4 invitation(id BIGSERIAL, email VARCHAR(320) NOT NULL, email_norm VARCHAR(320) NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE,
              status VARCHAR(16) NOT NULL CHECK IN ('PENDING','ACCEPTED','EXPIRED','REVOKED'), invited_by BIGINT NOT NULL,
              message VARCHAR(500), expires_at TIMESTAMPTZ NOT NULL, accepted_member_id BIGINT, accepted_at TIMESTAMPTZ,
              accepted_via VARCHAR(16) CHECK IN ('TOKEN','EMAIL_MATCH'), created_at, updated_at)
   UNIQUE INDEX (email_norm) WHERE status='PENDING'
   invitation_team(invitation_id FK, team_id FK, role VARCHAR(20) NOT NULL DEFAULT 'MEMBER', PK(invitation_id, team_id))
   invitation_grant(invitation_id FK, scope VARCHAR(20), resource_id BIGINT NULL, role VARCHAR(20), PK(invitation_id, scope, resource_id))
V5 member: status CHECK 확장 ('PENDING','ACTIVE','SUSPENDED','DEACTIVATED'), joined_via VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
              ('INVITE','APPROVAL','BOOTSTRAP','LEGACY'), approved_by BIGINT, approved_at, suspended_at, deactivated_at
   member_event(id, member_id, type VARCHAR(32) — INVITED/JOINED/APPROVED/SUSPENDED/REACTIVATED/DEACTIVATED/TEAM_ADDED/TEAM_REMOVED/KEYCLOAK_DISABLED_FAILED,
              actor_id BIGINT, detail VARCHAR(500), created_at)   -- 초대·상태 이력(권한 이력은 기존 grant_audit)
V6 team: kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD' ('STANDARD','EVERYONE'); "전체 구성원" 팀 1행 시드(kind EVERYONE, 삭제·이름 변경 불가)
   grant_entry: updated_at, updated_by 추가(PATCH 이력용)
```
기존 데이터: 기존 member는 전부 ACTIVE·LEGACY 그대로. 시드 시 모든 ACTIVE HUMAN을 EVERYONE 팀에 넣는다.

### 3.2 규칙
- **PENDING 격리**: `MemberMirrorFilter`가 PENDING 회원의 요청 중 `GET /api/org/me`·`/api/org/me/**` 외에는 403 `{"error":"승인 대기 중인 계정입니다"}`.
  gRPC `CheckPermission`은 PENDING/SUSPENDED/DEACTIVATED에 항상 거부(fail-closed) — wiki·alm이 자동으로 막힌다. `LookupMembers`는 ACTIVE만(기존).
- **EVERYONE 팀**: ACTIVE HUMAN 전원이 자동 소속(승인·초대 수락 시 추가, SUSPENDED/DEACTIVATED 시 제거). 스페이스·프로젝트 ADMIN이 "전체 구성원"에 VIEWER를
  주면 곧 공개 스페이스다. 수동 추가·제거 금지(400).
- **팀 리더**: `team_member.role=LEAD`는 자기 팀의 팀원 추가·제거·리더 지정 가능(GLOBAL ADMIN 아니어도). 팀 생성·삭제·이름 변경은 GLOBAL ADMIN.
- **grant 역할 변경**: `PATCH /api/org/grants/{id} {role}` — 삭제·재생성 대신 제자리 갱신, `grant_audit`에 CHANGE 기록. **마지막 GLOBAL ADMIN**(USER grant 기준, 팀 경유 제외)의
  강등·삭제·본인 비활성화는 409 `{"error":"마지막 전역 관리자는 내릴 수 없습니다"}`.
- **상태 전이**: ACTIVE→SUSPENDED(일시 정지, 되돌림 가능)→ACTIVE, ACTIVE|SUSPENDED→DEACTIVATED(퇴사; 되돌리려면 재초대). DEACTIVATED 시 Keycloak 계정 `enabled=false`
  (Admin API, 실패해도 우리 쪽 상태는 바뀌고 `member_event`에 KEYCLOAK_DISABLED_FAILED — 다음 로그인은 어차피 PENDING/차단). SUSPENDED는 Keycloak 안 건드림(세션은 refresh 때 거부).
- **초대 권한**: GLOBAL ADMIN은 무제한. 리소스(SPACE/PROJECT) ADMIN은 초대 가능하되 프리셋 grant는 **자기가 ADMIN인 리소스**로만, 팀 프리셋은 자기가 LEAD인 팀만. 전역 역할 프리셋은 GLOBAL ADMIN만.
- **만료**: 기본 7일(`platform.org.invitation.ttl`), 만료 스케줄러가 PENDING→EXPIRED. 재발송은 새 토큰·새 만료(이전 토큰 무효).

### 3.3 API (`/api/org`, 기존 유지 + 추가)
| 메서드 | 경로 | 요청 → 응답 | 권한 |
|---|---|---|---|
| GET | `/me` (확장) | → `{id, displayName, email, status, kind, globalRoles: ["ADMIN"|...], teams:[{id,name,role}], joinedVia}` | 본인(PENDING 포함) |
| GET | `/members?status=&kind=&q=&page=&size=` | 기본 `status=ACTIVE&kind=HUMAN`, `q`는 이름·이메일 부분일치 → `{items[{id,displayName,email,status,kind,joinedVia,createdAt}], page, size, total}` | 인증 |
| GET | `/members/{id}` | → 상세 + `teams[]` + `grants[]`(GLOBAL ADMIN 또는 본인) | 인증 |
| PATCH | `/members/{id}` | `{status?: SUSPENDED|ACTIVE|DEACTIVATED, displayName?}` | GLOBAL ADMIN(본인 DEACTIVATED 금지) |
| POST | `/members/{id}/approve` | PENDING→ACTIVE(+EVERYONE, 선택 `{teamIds[], grants[]}`) | GLOBAL ADMIN |
| GET | `/members/pending` | 승인 대기 목록 | GLOBAL ADMIN |
| POST | `/invitations` | `{emails[], teams:[{teamId, role}], grants:[{scope,resourceId,role}], message?}` → 생성된 초대 목록(각 `inviteUrl` — 메일 미설정 시 화면에서 복사, `mailSent: bool`) | §3.2 초대 권한 |
| GET | `/invitations?status=&q=&page=` | 목록(토큰 없음, `inviteUrl`은 PENDING만 재노출 — 링크 복사용) | GLOBAL ADMIN(리소스 ADMIN은 자기가 보낸 것만) |
| POST | `/invitations/{id}/resend` | 새 토큰·만료, 메일 재발송 | 동일 |
| DELETE | `/invitations/{id}` | PENDING→REVOKED | 동일 |
| GET | `/invitations/by-token/{token}` | 내부용(auth-server가 호출, 서비스 간): `{email, status, expiresAt}` — 토큰 유효성만 | 내부 |
| POST | `/invitations/accept` | 내부용: auth-server가 로그인 성공 후 `{token, memberId, email}`로 호출 → 수락 처리(TOKEN 경유 표시) | 내부 |
| PATCH | `/grants/{id}` | `{role}` | 리소스 ADMIN / GLOBAL ADMIN |
| GET | `/teams?q=` (확장) | + `kind`, `memberCount`, `myRole` | 인증 |
| POST/DELETE | `/teams/{id}/members` (확장) | LEAD도 허용(자기 팀), EVERYONE은 400 | GLOBAL ADMIN 또는 해당 팀 LEAD |
| PATCH | `/teams/{id}/members/{memberId}` | `{role: LEAD|MEMBER}` | 동일 |
| GET | `/members/{id}/events`, `/invitations/{id}/events` | 이력 | GLOBAL ADMIN |
내부용 두 개는 게이트웨이 노출 금지(경로 `/internal/org/**`로 두고 서비스 간 헤더 `X-Internal-Token`(env) 검사 — gRPC 채널이 무인증인 현 전제와 같은 수준).

### 3.4 메일·Keycloak
- 메일: `platform.org.mail.{host,port,username,password,from,base-url}`(spring-boot-starter-mail, wiki의 `WIKI_MAIL_*`과 같은 값을 env로 공유). 미설정이면 `mailSent=false`, 화면이 링크 복사 안내.
  본문: 초대자 이름·메시지·팀·링크·만료일, 한국어.
- Keycloak Admin: realm에 confidential 클라이언트 `platform-admin`(service account, `realm-management: manage-users, view-users`) 신설(infra `realm-export.json`, 시크릿은 env `KC_ADMIN_CLIENT_SECRET`).
  org-service `KeycloakAdminClient`(JDK HttpClient, client_credentials): `disableUserByEmail`, `enableUserByEmail`. 후속: SMTP 생기면 `executeActionsEmail(UPDATE_PASSWORD)` 로 비밀번호 사용자 사전 생성.

## 4. auth-server
- `GET /invite/{token}`: org 내부 API로 검증 → 유효하면 세션에 `invite_token` 저장 후 Keycloak 로그인으로 리다이렉트(`login_hint=email`, `kc_action`은 없음). 무효·만료면 안내 페이지(HTML 한 장, 한국어).
- `LoginSuccessHandler`: users 행 생성 뒤 세션에 `invite_token`이 있으면 org 내부 `/invitations/accept` 호출(실패해도 로그인은 진행 — 이메일 매칭 경로가 있다).
- `GET /api/me`: `roles` **배열 전체** 추가(기존 `role` 유지). 전역 관리자 판정은 프론트가 `/api/org/me.globalRoles`로 — auth-server는 org를 모른다.

## 5. 공용 관리 화면 — `@chanho/org-admin` (design-system 리포 `packages/org-admin`)
- React 19 + `@chanho/react` DS, 라우팅은 호스트가 제공(`react-router` peer). 진입 `OrgAdminApp({ basePath, api, currentUser, links })`:
  `api`는 호스트가 주입하는 인증 fetch(`(path, init) => Promise<Response>`) — 토큰·쿠키·게이트웨이 경로는 호스트 책임. 패키지는 `/api/org/*`만 안다.
- 화면: **사용자**(목록·검색·상태/종류 필터·페이지, 상세 드로어: 상태 변경·팀·권한·이력), **초대**(새 초대: 이메일 여러 개(붙여넣기 분리)·팀·전역 역할·리소스 권한 프리셋·메시지 → 결과에 링크 복사 버튼; 목록: 대기/수락/만료/철회·재발송·철회),
  **팀**(목록·생성·리더 지정·팀원 추가/제거, 전체 구성원 팀은 읽기 전용), **전역 역할**(GLOBAL grant 목록·부여·역할 변경·회수, 마지막 관리자 보호 문구), **승인 대기**(PENDING 목록·승인/거절=DEACTIVATED).
- 공통: `getByRole` 테스트(vitest + testing-library), 한국어 문구, 토큰 없음. 빈·로딩·에러 상태. 리소스 권한 프리셋의 리소스 이름은 호스트가 `resolveResource(scope, id)`로 준다(패키지는 위키·ALM을 모른다).
- **승인 대기 화면**(`PendingApprovalGate`): 호스트가 `/api/org/me.status==='PENDING'`이면 셸 대신 이 컴포넌트를 그린다.
- 발행: `packages/org-admin` → `@chanho4702/org-admin`(publish.yml에 세 번째 패키지 추가), 소비는 `npm:` alias `@chanho/org-admin`.
- 마운트: wiki-front `/admin/org/*`(기존 `/admin/teams`는 여기로 리다이렉트), alm-front `/admin/org/*`(ALM 세션).

## 6. wiki-front 소비(이 세션)
- `/admin/org/*` 마운트, ⚙ 메뉴에 "사용자·팀", `AuthGate` 뒤에 `PendingApprovalGate`. 전역 관리자 판정을 `/api/org/me.globalRoles`로 통일(기존 "관리자 엔드포인트 찔러보기" 제거).
- 스페이스 권한 화면: 대상 선택기에 검색(`/members?q=`)과 "초대하기" 링크(초대 화면으로, 스페이스 권한 프리셋 미리 채움).

## 7. infra
- `infra/keycloak/realm-export.json`: `platform-admin` 클라이언트(service account) 추가, 시크릿은 `${KC_ADMIN_CLIENT_SECRET}` 치환(compose env). `registrationAllowed` 유지(§2), `verifyEmail`은 SMTP 뒤.
- compose/env: org-service에 `KC_ADMIN_CLIENT_SECRET`, `PLATFORM_MAIL_*`(선택), `ORG_INTERNAL_TOKEN`; auth-server에 `ORG_INTERNAL_TOKEN`·`ORG_SERVICE_URI`.

## 8. 단계
- **U1 org-service**(V4~V6, 규칙, API, 메일 선택, Keycloak 관리 클라이언트, 테스트) — 백엔드 레인
- **U2 auth-server**(/invite, accept 호출, /api/me roles) — U1 레인이 이어서
- **U3 `@chanho/org-admin`**(패키지·5화면·테스트·발행) — 프론트 레인, 계약은 §3.3
- **U4 wiki-front 마운트 + infra(realm·env)** — 이 세션
- ALM 세션: `member_profile`(V7~), alm-front 마운트·프로젝트 권한 화면 소비, ALM 백엔드 관리자 판정을 org gRPC로.

## 9. 열린 항목(기본값으로 진행, ⚠️ 표시)
- ⚠️ 리소스 ADMIN의 초대 허용 범위 — 기본: 허용(프리셋은 자기 리소스로 제한). 조직 정책상 GLOBAL ADMIN만이면 프로퍼티로 끈다.
- ⚠️ SUSPENDED 사용자의 기존 세션 — refresh 때 거부(AT 만료까지 최대 수 분 유효). 즉시 차단은 auth-server RT 폐기 API 후속.
- ⚠️ 이메일 검증 없는 비밀번호 가입의 초대 수락은 토큰 링크 경유만(§2). SMTP 생기면 `verifyEmail` 켜고 완화.
