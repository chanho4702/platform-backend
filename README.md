# platform-backend

[![CI](https://github.com/chanho4702/platform-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/chanho4702/platform-backend/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-24-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)
![gRPC](https://img.shields.io/badge/gRPC-Protobuf-244C5A?logo=google&logoColor=white)
![OpenSearch](https://img.shields.io/badge/OpenSearch-2.19.0-005EB8?logo=opensearch&logoColor=white)

ALM·Wiki 플랫폼의 **횡단 서비스 멀티모듈 repo**. 도메인 서비스(wiki-backend 등)가 공통으로 기대는 것들이 여기 모여 있다.

> 별도 git repo: `github.com/chanho4702/platform-backend` (브랜치 `main`). 우산 repo(`chanho4702/infra-settings`)에서는 gitignore 됨.

## 모듈

| 모듈 | 역할 | 포트 (운영 / dev) |
|---|---|---|
| `common-proto` | gRPC 계약 + 이벤트 스키마. GitHub Packages 발행 (`com.platform:common-proto`) | — |
| `org-service` | 조직·팀·RBAC. REST(게이트웨이 경유 `/api/org/**`) + gRPC `PermissionService` | 9130 / 19130 · gRPC 9131 / 19131 |
| `search-service` | 통합 검색 GraphQL + 재색인 관리 REST. Redis Streams 소비 → OpenSearch 색인 | 9140 / 19140 |

dev 오프셋 규약은 운영 포트 **+10000**이다(도커 배포판과 공존).

---

## common-proto — 계약

`src/main/proto/platform/` 아래 세 묶음.

| 패키지 | 내용 |
|---|---|
| `platform.org.v1` | `PermissionService` — `CheckPermission` · `ListUserGrants` · `CreateGrant` · `RevokeGrant` · `ListUserTeams` · `ValidatePrincipals` · `LookupMembers` · `LookupTeams` |
| `platform.wiki.v1` | `WikiContentService` — `GetPageContent` · `GetAttachmentMeta` · `ListPageContents`(stream) · `ListAttachments`(stream) |
| `platform.events.v1` | `EventEnvelope` + 도메인 이벤트(페이지·스페이스·첨부). Redis Streams 페이로드 |

**설계 원칙 — 이벤트는 본문을 싣지 않는다.** 소비자는 "무엇이 변했나"만 받고, 내용은 소유 서비스의 gRPC로 가져간다. 큰 페이지가 스트림에 그대로 실리면 Redis 메모리와 재생 비용이 본문 크기에 비례해 커지기 때문이다. `platform.wiki.v1`이 존재하는 이유가 이것이다.

`GetPageContent`의 상태 계약은 명시적으로 갈라져 있다:

- `NOT_FOUND` = 이미 삭제된 페이지 → 소비자는 **삭제로 간주하고 ACK**
- `FAILED_PRECONDITION` = 참조가 깨진 고아 페이지 → 삭제가 아니므로 **재시도·DLQ**

둘을 뭉뚱그리면 데이터 수리가 필요한 상태를 조용히 색인에서 지우게 된다.

> **알려진 갭**: gRPC 채널에 인증이 없다. 내부망 전용(호스트 포트 미개방)을 전제로 defer된 플랫폼 전역 과제다.

### 발행 / 소비

`v*` 태그 push 시 CI가 태그 버전으로 GitHub Packages에 발행한다(`-PprotoVersion=<태그에서 v 제거>`). 로컬 기본 버전은 `0.1.0-SNAPSHOT`.

```gradle
implementation 'com.platform:common-proto:<버전>'   // + GitHub Packages 저장소 인증
```

같은 repo의 모듈은 발행본이 아니라 `implementation project(':common-proto')`로 직접 참조한다 — proto를 고칠 때마다 발행하지 않아도 되고 버전 어긋남이 생기지 않는다.

> **발행 순서 주의**: 소비자 repo(wiki-backend 등)는 **발행 CI가 끝난 뒤** 푸시한다. 같은 시각에 밀어넣으면 `Could not find com.platform:common-proto:<버전>`으로 소비자 CI가 깨진다.

---

## org-service

REST `/api/org/**`(게이트웨이 경유) + gRPC `PermissionService`(:9131, 내부 전용). DB는 `orgdb`(PostgreSQL), 스키마는 Flyway.

### 권한 모델

`grant_entry` 단일 원장 — subject(`USER`|`TEAM`) × resource(`GLOBAL`|`SPACE`|`PROJECT`) × role(`VIEWER` < `EDITOR` < `ADMIN`).

판정은 직접 grant와 팀 grant를 병합해 **최고 role**을 취하고, `GLOBAL` grant는 전 리소스에 적용된다.
최초 관리자는 `PLATFORM_BOOTSTRAP_ADMIN_ID`로 시드한다.

### 이름 조회 (0.15.0)

- **`LookupMembers(emails, usernames)` → `MemberMatch[]`** — 이메일(또는 `username` = 이메일 local-part)로 우리 계정을 찾는다. 컨플루언스 이관이 원본 작성자·제한 주체를 짝지을 때 쓴다.
- **`LookupTeams(names)` → `TeamMatch[]`** — 원본 그룹 이름으로 우리 팀을 찾는다.

**`GetMembers(ids)` → `MemberInfo[]`**(0.16.0)는 방향이 반대다 — id를 아는 쪽이 이름·이메일·상태·종류를
묻는다(ALM 알림이 담당자 주소를 얻는 창구). `LookupMembers`와 달리 **활성 여부로 거르지 않는다**:
퇴사자에게 알림을 안 보내는 것과 퇴사자 이름을 화면에 못 그리는 것은 다른 문제라, `status`를 실어 주고
판단은 호출측에 맡긴다. 없는 id는 응답에서 빠지고, 상한은 200이다.

둘 다 trim + 대소문자 무시로 대조하고 **매칭된 것만** 돌려준다(못 찾은 질의는 응답에서 빠진다 — 호출측이 fail-closed로 닫는다). 활성 멤버만 보고, 한 질의에 후보가 둘 이상이면 매칭으로 세지 않는다 — `member.email`에 UNIQUE가 없어 누구인지 모르는 채로 하나를 고르면 남의 이름으로 문서가 쓰인다. 한 요청의 항목 상한은 200이고 넘으면 `INVALID_ARGUMENT`다.

> ⚠️ **기본값이 실행 방식에 따라 다르다.** `application.yml`은 `${PLATFORM_BOOTSTRAP_ADMIN_ID:}` — 즉 **코드 기본값은 빈 값이고, 비어 있으면 `BootstrapAdminSeeder`가 시딩을 건너뛴다.** `gradlew :org-service:bootRun`으로 직접 띄우면 아무도 자동으로 관리자가 되지 않는다.
> 반면 **compose는 `${PLATFORM_BOOTSTRAP_ADMIN_ID:-1}`로 1을 주입**하므로 컨테이너 스택에서는 사용자 1이 재기동마다 GLOBAL ADMIN으로 복구된다. 운영 배포 전 `.env`에 실제 관리자 id를 명시하거나 빈 값으로 두어 비활성화할 것.

### 사용자 초대 · 상태 · 팀 권한 (V4~V6, U1)

**초대 없는 가입은 차단된다.** 로그인 전에는 그 사람의 id가 없어 팀·권한을 미리 줄 수 없으므로 초대의 키는
**이메일**이고, 토큰은 링크 검증·`login_hint`·수락 추적용이다. 처음 로그인한 사람은 `MemberMirrorFilter`가
`PENDING`으로 만들고, 이메일이 일치하는 살아 있는 초대가 있으면 그 자리에서 소진해 활성 사용자로 만든다.
초대가 없으면 `GET /api/org/me`와 그 하위 말고는 전부 `403 {"error":"승인 대기 중인 계정입니다"}`다.

> **토큰 원문은 저장하지 않는다(sha256만).** 그래서 `inviteUrl`은 **생성·재발송 응답에만** 담기고 목록에서는
> 항상 `null`이다. 링크가 다시 필요하면 `POST /invitations/{id}/resend`(새 토큰, 이전 링크는 그 순간 죽음)를
> 쓴다. `?mail=false`면 메일 없이 링크만 다시 받는다.

**이메일 대조만으로 소진되는 로그인 경로는 제한된다.** Keycloak 이메일 검증(`verifyEmail`)이 꺼져 있는 동안
비밀번호 가입자가 남의 이메일을 적어 남의 초대를 가로챌 수 있기 때문이다. 기본값은 구글뿐이고
(`ORG_INVITATION_EMAIL_MATCH_PROVIDERS=GOOGLE`, JWT `provider` 클레임으로 판정), 나머지는 초대 링크를
타야 한다(auth-server `/invite/{token}` → 내부 `accept`).

**상태 판정은 org 하나로 통일된다.** gRPC `CheckPermission`은 `ACTIVE`가 아닌 멤버를 grant를 보기 전에
거부한다(fail-closed) — 정지·퇴사자가 권한을 그대로 들고 있어도 wiki·alm이 문서를 열어 주지 않는다.
member 행이 아예 없으면 상태로 막지 않고 평소의 grant 판정에 맡긴다(그러지 않으면 미러링 순서에 따라
기존 사용자가 무작위로 차단된다).

> **이 거부는 정상 응답이지 오류가 아니다.** `UNAVAILABLE` 같은 상태 코드로 올리지 않는다 — 소비자가
> "org가 죽었다"와 "이 사람이 막혔다"를 구분할 수 있어야 한다. 대신 `CheckPermissionResponse.denied_reason`
> (0.16.0)이 이유를 문자열로 싣는다: `PENDING`·`SUSPENDED`·`DEACTIVATED`(상태) / `NO_GRANT`·`INSUFFICIENT_ROLE`(권한),
> 허용이면 빈 문자열. 값은 앞으로 늘 수 있으니 모르는 값은 일반 거부로 다룬다.

**"전체 구성원" 팀**(`team.kind='EVERYONE'`)에는 활성 사람 멤버 전원이 자동으로 속한다. 수동 추가·제거·삭제·
이름 변경은 `400`이다. 스페이스·프로젝트 ADMIN이 이 팀에 VIEWER를 주면 그것이 곧 "공개"다.

| 메서드 | 경로 | 인가 | 요청 → 응답 |
|---|---|---|---|
| `GET` | `/me` | 인증(PENDING 포함) | → `{id, displayName, email, avatarUrl, avatarUpdatedAt, status, kind, joinedVia, globalRoles[], teams[{id,name,kind,role}]}` |
| `GET` | `/members` | 인증 | **배열**(기존 계약 유지). `status`·`kind`·`q`(이름·이메일 부분일치) 선택, 기본 `status=ACTIVE&kind=HUMAN`. 전부는 `status=ALL&kind=ALL` |
| `GET` | `/members/page` | 인증 | 같은 필터 + `page`·`size` → `{items[], page, size, total}` |
| `GET` | `/members/pending` | GLOBAL ADMIN | 승인 대기 목록 |
| `GET` | `/members/{id}` | 인증 | 상세 + `teams[]`, `grants[]`는 본인·GLOBAL ADMIN에게만(아니면 필드 없음) |
| `PATCH` | `/members/{id}` | GLOBAL ADMIN | `{status}` → 상세. **표시 이름은 못 바꾼다** — `MemberMirrorFilter`가 요청마다 JWT `name`으로 덮어쓴다(원천은 Keycloak) |
| `POST` | `/members/{id}/approve` | GLOBAL ADMIN | `{teams?[{teamId,role}], grants?[{scope,resourceId,role}]}` → 상세(ACTIVE·`joinedVia=APPROVAL`·전체 구성원 합류) |
| `GET` | `/members/{id}/events` | GLOBAL ADMIN | 초대·상태 이력 |
| `POST` | `/invitations` | §초대 권한 | `{emails[], teams?[{teamId,role}], grants?[{scope,resourceId,role}], message?}` → `201` 초대 배열(각 `inviteUrl`·`mailSent`) |
| `GET` | `/invitations` | GLOBAL ADMIN(리소스 ADMIN은 자기가 보낸 것만) | `status`·`q`·`page`·`size` → `{items[], page, size, total}`, `inviteUrl`은 항상 `null` |
| `POST` | `/invitations/{id}/resend` | 동일 | `?mail=true|false` → 새 토큰·새 만료 + `inviteUrl` |
| `DELETE` | `/invitations/{id}` | 동일 | PENDING → REVOKED, `204` |
| `GET` | `/invitations/{id}/events` | GLOBAL ADMIN | 초대 이력 |
| `GET` | `/grants?resourceType=&resourceId=` | 리소스 ADMIN / GLOBAL ADMIN | 항목에 `id`(PATCH·DELETE 대상)와 `subjectName`(USER면 표시 이름, TEAM이면 팀 이름, 못 찾으면 `사용자 #id`) |
| `PATCH` | `/grants/{id}` | 리소스 ADMIN / GLOBAL ADMIN | `{role}` → 제자리 갱신(`grant_audit`에 `GRANT_CHANGED`) |
| `GET` | `/teams` | 인증 | `q` 선택. 항목에 `kind`·`memberCount`·`myRole` 추가(기존 필드 불변) |
| `GET` | `/teams/{id}/members` | 인증 | `[{memberId, displayName, email, role}]` — `email`은 동명이인을 가르는 단서 |
| `PUT`/`DELETE` | `/teams/{id}/members/{memberId}` | GLOBAL ADMIN **또는 그 팀 LEAD** | 팀원 추가·제거 |
| `PATCH` | `/teams/{id}/members/{memberId}` | 동일 | `{role: LEAD|MEMBER}` |

> **요청 키가 두 가지인 이유.** grant 생성(`POST /grants`)은 기존대로 `resourceType`을 받고, 초대·승인의
> 권한 **프리셋**은 `scope`를 받는다. 프리셋은 "지금 만들 grant"가 아니라 "수락하면 만들 조건"이라
> 같은 이름을 쓰면 두 요청이 같은 것으로 읽힌다. 프론트가 이 구분에 맞춰져 있다.

**초대 권한.** GLOBAL ADMIN은 무제한. 리소스(SPACE/PROJECT) ADMIN도 초대할 수 있지만
(`ORG_INVITATION_RESOURCE_ADMIN=false`로 끈다) 권한 프리셋은 **자기가 ADMIN인 리소스**로만, 팀 프리셋은
**자기가 LEAD인 팀**으로만, 전역 역할 프리셋은 GLOBAL ADMIN만이다. 아무 리소스의 ADMIN도 아니면
`403 {"error":"초대 권한이 없습니다"}`.

**마지막 전역 관리자 보호.** `PATCH /grants/{id}` 강등, `DELETE /grants/{id}`, `PATCH /members/{id}`의
SUSPENDED·DEACTIVATED는 그 사람이 마지막 GLOBAL ADMIN이면 `409 {"error":"마지막 전역 관리자는 내릴 수 없습니다"}`다.
세는 기준은 **USER 직접 grant**뿐이다 — 팀 경유 관리자는 그 팀에서 사람이 빠지면 조용히 0이 되므로
"아직 한 명 남아 있다"의 근거가 되지 못한다. 자기 계정 비활성화도 `409`다.

**상태 전이.** `ACTIVE ↔ SUSPENDED`, `ACTIVE|SUSPENDED → DEACTIVATED`. `DEACTIVATED → ACTIVE`는 막혀 있고
(`409 비활성된 계정은 재초대로만 되돌릴 수 있습니다`) 재초대가 유일한 복귀 경로다 — 그때 Keycloak 계정을 다시 연다.
`DEACTIVATED`는 Keycloak 계정을 `enabled=false`로 잠그고, 실패해도 우리 상태는 바뀌며
`member_event(KEYCLOAK_DISABLED_FAILED)`에 남는다(관리자가 퇴사 처리를 못 하는 편이 더 나쁘다).

**서비스 간 전용 경로** — 게이트웨이가 라우팅하지 않는다. 인증은 `X-Internal-Token` 헤더 하나뿐이고,
`ORG_INTERNAL_TOKEN`이 비어 있으면 헤더와 무관하게 전부 `403`이다(fail-closed).

| 메서드 | 경로 | 요청 → 응답 |
|---|---|---|
| `GET` | `/internal/org/invitations/by-token/{token}` | → `{email, status, expiresAt}`, 무효면 `404 {"error":"유효하지 않은 초대입니다"}` |
| `POST` | `/internal/org/invitations/accept` | `{token, memberId, email, displayName?}` → `{accepted, status, invitationId}` (이메일 불일치면 `accepted:false, status:"EMAIL_MISMATCH"`) |

| 변수 | 기본값 | 용도 |
|---|---|---|
| `ORG_INTERNAL_TOKEN` | (빈 값) | `/internal/org/**` 게이트. 비면 내부 API 전체 차단 |
| `ORG_INVITATION_TTL` | `P7D` | 초대 유효기간(ISO-8601) |
| `ORG_INVITATION_BASE_URL` (= `PLATFORM_ORG_INVITATION_BASE_URL`) | (빈 값) | 초대 링크 호스트. 경로는 auth-server `/invite/{token}`. 비면 상대 경로. 완화 바인딩 이름이 우선한다(compose가 쓰는 쪽) |
| `ORG_INVITATION_EMAIL_MATCH_PROVIDERS` | `GOOGLE` | 토큰 없이 이메일 대조만으로 소진 가능한 로그인 경로(콤마 구분) |
| `ORG_INVITATION_RESOURCE_ADMIN` | `true` | 리소스 ADMIN의 초대 허용 |
| `ORG_INVITATION_EXPIRY_CRON` | `0 */10 * * * *` | 만료 배치 |
| 초대 메일 | — | SMTP 설정은 org-service가 아니라 **플랫폼 메일 설정**이 정본이다(아래 절). 꺼져 있으면 `mailSent:false`이고 화면이 링크 복사를 안내한다 |
| `KEYCLOAK_ISSUER_URI` | (빈 값) | Keycloak realm 주소(`.../realms/{realm}`) |
| `KC_ADMIN_CLIENT_ID` / `KC_ADMIN_CLIENT_SECRET` | `platform-admin` / (빈 값) | 계정 비활성/활성용 service account. 시크릿이 비면 no-op |

> ⚠️ **인프라 후속(U4).** realm에 `platform-admin`(confidential, service account, `realm-management: manage-users, view-users`)
> 클라이언트를 추가하고 compose에 위 env를 주입해야 계정 잠금이 실제로 동작한다. 그전까지는 no-op으로 흘러가고
> `member_event`에만 흔적이 남는다. 게이트웨이는 `/internal/**`을 라우팅하지 않아야 한다.

---

### 플랫폼 메일 (V8, M1)

**발송은 org-service 한 곳에서만 한다.** 위키·ALM은 JavaMail을 버리고 `POST /internal/org/mail`로 넘긴다 —
설정·자격증명·재시도·발송 로그가 서비스마다 갈라지면 "메일이 안 갔다"를 어디서 봐야 하는지부터 달라진다.
**수신자 결정(구독·차단 상태)·커밋 뒤 배치·다이제스트 스케줄은 여전히 각 서비스 몫**이다: 누가 받아야 하는지는
그 서비스만 안다.

설정은 **DB 한 행**(`mail_setting`, `id=1`)이 정본이고 관리 화면이 편집한다. `MAIL_MODE`는 설치 시점의
인프라 선택(어떤 컨테이너를 띄우고 무엇을 초기값으로 심을지)이고, `MAIL_SEED_*`는 **행이 없을 때 최초 1회**만
쓰인다 — 기동마다 env로 덮으면 화면에서 고친 값이 재배포에 조용히 되돌아간다.

발송은 **outbox**(`mail_outbox`)를 거친다. 넣기는 호출측 트랜잭션에 참여하고(초대가 롤백되면 그 메일도
사라진다), 워커가 5초마다 배치 20건씩 집어 보낸다. 행은 `FOR UPDATE SKIP LOCKED`로 잠근다 — 인스턴스가
배포 중 잠깐 둘로 겹쳐도 같은 메일이 두 번 나가지 않는다. 실패는 지수 백오프(30초·1분·2분·4분)로 최대
**5회**까지 다시 시도하고 그다음 `FAILED`로 눕는다. 종결분(SENT·FAILED)은 30일 뒤 하루 한 번 정리한다.

> **`mailSent`의 뜻이 바뀌었다.** 초대 응답의 `mailSent`는 이제 "보냈다"가 아니라 **"큐에 넣었다"**이다
> (`enabled`이고 큐잉에 성공하면 `true`). 실제 배달 결과는 발송 로그에 남는다. 예전처럼 초대 트랜잭션 안에서
> 동기 발송하지 않는다 — 초대 생성이 SMTP 지연에 묶이고 발송 실패가 초대를 롤백시켰다.

**SMTP 비밀번호는 AES-GCM으로만 저장된다**(`ORG_SETTINGS_ENC_KEY`, 32바이트 hex = 64자). 키가 없으면
비밀번호 저장이 `400 {"error":"메일 비밀번호를 저장하려면 ORG_SETTINGS_ENC_KEY가 필요합니다"}`로 거부되고,
나머지 설정(호스트·포트·발신자)은 그대로 동작한다. 평문 폴백을 두지 않는 이유는 "설정은 됐는데 사실 평문"인
상태가 조용히 운영으로 나가기 때문이다. 비밀번호는 **어떤 응답에도 실리지 않는다** — `passwordSet` 불리언만 나간다.

| 메서드 | 경로 | 인가 | 요청 → 응답 |
|---|---|---|---|
| `GET` | `/api/org/settings/mail` | GLOBAL ADMIN | → `{enabled, mode, host, port, username, passwordSet, tls, fromAddress, fromName, updatedAt, updatedBy}` |
| `PUT` | `/api/org/settings/mail` | GLOBAL ADMIN | 같은 필드 + `password?` — **생략=유지, `""`=삭제, 값=교체**. `enabled`면 `host`·`port`·`fromAddress` 필수 |
| `POST` | `/api/org/settings/mail/test` | GLOBAL ADMIN | `{to?}`(기본 = 요청자 JWT 이메일) → **동기 발송**, `{ok, error?}`. 실패해도 `200`이고 SMTP 문구가 그대로 담긴다 |
| `GET` | `/api/org/settings/mail/log?status=&page=&size=` | GLOBAL ADMIN | → `{items[{id,to,subject,source,status,attempts,lastError,createdAt,sentAt}], page, size, total}`. 본문은 담지 않는다 |
| `POST` | `/api/org/settings/mail/log/{id}/retry` | GLOBAL ADMIN | `FAILED` → `PENDING`(시도 0으로 초기화), `204`. 다른 상태면 `409 실패한 발송만 다시 보낼 수 있습니다` |

**서비스 간 전용 경로** — 게이트웨이가 라우팅하지 않고 `X-Internal-Token`만이 인증이다.

| 메서드 | 경로 | 요청 → 응답 |
|---|---|---|
| `POST` | `/internal/org/mail` | `{to[], subject, text, html?, source}` → `202 {accepted, disabled}`. `to` 최대 100(넘으면 `400`), 빈 주소는 버리고 중복은 하나로 친다. `disabled:true`는 오류가 아니라 "메일이 꺼져 있음"이다 |
| `GET` | `/internal/org/mail/status` | → `{enabled}` — 소비자의 "메일 켜짐" UI용(소비자 쪽에서 60초 캐시). 켜져 있어도 호스트·보내는 주소가 비면 `false`다 |

| 변수 | 기본값 | 용도 |
|---|---|---|
| `MAIL_MODE` | `none` | 설치 시 고른 인프라 — `none`·`external`·`relay`·`full`·`dev`. 화면 배지용 안내값이고 동작을 바꾸지 않는다 |
| `ORG_SETTINGS_ENC_KEY` | (빈 값) | SMTP 비밀번호 암호화 키(32바이트 hex). 비면 비밀번호 저장이 `400`. 형식이 틀리면 **기동을 세운다** |
| `MAIL_SEED_ENABLED` | `false` | 최초 시드값 — 행이 없을 때만 |
| `MAIL_SEED_HOST` / `_PORT` | (빈 값) / `587` | 〃 |
| `MAIL_SEED_USERNAME` / `_PASSWORD` | (빈 값) | 〃. 비밀번호는 `ORG_SETTINGS_ENC_KEY`가 있을 때만 심는다(없으면 경고만 남기고 건너뛴다) |
| `MAIL_SEED_TLS` | `STARTTLS` | `NONE` · `STARTTLS` · `SSL` |
| `MAIL_SEED_FROM_ADDRESS` / `_FROM_NAME` | (빈 값) | 〃 |
| `MAIL_SMTP_TIMEOUT_MS` | `10000` | 연결·읽기·쓰기 타임아웃. 죽은 서버 하나가 큐 전체를 세우지 않게 한다 |
| `MAIL_OUTBOX_POLL_MS` / `_BATCH` | `5000` / `20` | 워커 폴링 주기·배치 크기 |
| `MAIL_OUTBOX_RETENTION_DAYS` / `_CLEANUP_CRON` | `30` / `0 40 4 * * *` | 종결분 보관 기간·정리 배치 |

> 기존 `PLATFORM_MAIL_HOST`/`_PORT`/`_USERNAME`/`_PASSWORD`/`_FROM`은 **제거됐다**. 어디에도 설정돼 있지
> 않아 호환 계층을 두지 않았다 — 남겨 두면 "env에도 있고 DB에도 있는" 두 정본이 생긴다.

---

### 아바타 · 멤버 프로필 (V7)

프로필 사진은 플랫폼 공통이다 — ALM 담당자 셀, 위키 작성자, 보드 코멘트가 같은 얼굴을 본다.
사용자 디렉터리(`GET /api/org/members`)가 여기 있으니 아바타도 여기 둔다(2026-09-05에
alm-backend `user_preference.avatar_key`에서 이관, ALM 쪽은 V21에서 제거).

`member` 테이블은 넓히지 않고 **별도 테이블 `member_profile`**(V7)에 둔다: `member`는 auth-server
클레임의 JIT 미러라 로그인이 지나갈 때마다 갱신되는 행이고, 사용자가 올린 자산의 수명은 그것과 다르다.

| 메서드 | 경로 | 인가 | 응답 |
|---|---|---|---|
| `PUT` | `/api/org/me/avatar` (multipart `file`) | 인증(본인) | `200 { memberId, avatarUrl, updatedAt }` |
| `DELETE` | `/api/org/me/avatar` | 인증(본인) | `204` |
| `GET` | `/api/org/members/{memberId}/avatar` | 인증 | 바이트(원본 타입, `private, max-age=300`, `nosniff`), 없으면 `404` |
| `GET` | `/api/org/me` | 인증(본인) | `{ id, displayName, email, avatarUrl, avatarUpdatedAt, … }` — U1에서 `status`·`kind`·`joinedVia`·`globalRoles`·`teams`가 더 붙었다(위 절) |
| `GET` | `/api/org/members` | 인증 | 기존 항목에 `avatarUrl`·`avatarUpdatedAt`(둘 다 nullable) 추가 — 기존 필드 불변. 배열 그대로이고, 기본 필터만 `ACTIVE`·`HUMAN`으로 좁아졌다(위 절) |

`avatarUrl`은 `/api/org/members/{id}/avatar?v={epochMillis}`이고 `?v=`는 `avatar_updated_at`의
epoch millis다. 프로필 전체의 `updated_at`과 분리해 두어 아바타와 무관한 갱신이 이미지 URL을 흔들지 않는다.

> **`<img src>`에 그대로 넣을 수 없다.** 바이트 엔드포인트가 Bearer 인증을 요구하는데 브라우저는
> `<img>` 요청에 Authorization 헤더를 붙이지 않아 401이 난다. 프론트는 fetch로 바이트를 받아
> object URL을 만들어 쓴다 — `avatarUrl`은 "아바타가 있다"는 신호이자 fetch 대상 경로다.

형식은 클라이언트가 보낸 `Content-Type`이 아니라 **매직 바이트**로 판별한다(PNG·JPEG·WebP만, 2MB 이하).
SVG가 프로필 사진 이름으로 들어와 인라인 실행되면 안 되기 때문이다. 거부 문구(`{"error"}`, 400)는
`아바타는 2MB 이하 이미지여야 합니다` · `아바타는 PNG·JPG·WebP 이미지만 올릴 수 있습니다` ·
`빈 파일은 올릴 수 없습니다`이고, 조회 실패는 `아바타가 없습니다`(404)다.

바이트는 `avatars/{memberId}/{uuid}.{ext}` 키로 S3 호환 저장소(MinIO 버킷 `member-avatars`)에 넣고,
`platform.org.avatar.s3.enabled`가 꺼져 있으면 `ORG_FILES_DIR` 아래 로컬 파일로 떨어진다(**기본값**,
dev 오프셋 클러스터는 MinIO 없이 뜬다). 다시 올리면 이전 오브젝트를 커밋 뒤에 지우고, 삭제 실패는
예외가 아니라 `WARN` 로그다(키가 로그에 남으니 손으로 지운다). 저장소 장애는 `503`으로 전파한다.

| 변수 | 기본값 | 용도 |
|---|---|---|
| `ORG_FILES_DIR` | `./data/avatars` | 로컬 파일 폴백 경로 |
| `ORG_S3_ENABLED` | `false` | S3 호환 저장소 사용 여부 |
| `ORG_S3_BUCKET` / `ORG_S3_REGION` | `member-avatars` / `us-east-1` | 버킷·리전 |
| `ORG_S3_ENDPOINT` / `ORG_S3_PATH_STYLE_ACCESS` | (빈 값) / `true` | MinIO 내부 DNS endpoint |
| `ORG_S3_ACCESS_KEY` / `ORG_S3_SECRET_KEY` | (빈 값) | 자격증명 — 둘 다 주거나 둘 다 비운다 |
| `ORG_MAX_AVATAR_MB` | `4` | 멀티파트 컨테이너 상한. 서비스 상한(2MB)보다 넉넉해야 사용자가 한국어 400을 본다 |

> **알려진 갭 / 후속**: 관리자가 남의 아바타를 올리는 경로(`PUT /api/org/members/{id}/avatar`, ADMIN 전용)는
> 아직 없다 — AGENT 멤버의 얼굴은 지금 넣을 수 없다. 저장소 코드(`profile/AvatarStorage` 3종)는
> alm-backend `attachment/AttachmentStorage`의 최소 복제다. 쓰는 곳이 둘뿐이라 common-starter로
> 끌어올리지 않았다(AWS SDK 의존이 리소스 서버 다섯 곳 전부에 붙는다). 세 번째 소비자가 생기면 그때 옮긴다.

### OpenAPI (`GET /v3/api-docs`)

org-service의 REST 계약을 코드에서 뽑아 OpenAPI 3.1 JSON으로 낸다. springdoc `3.0.3`(Boot 4.0.x 라인 — 3.1.x는 Boot 4.1용이다), **UI 없음**. 공개 문서는 myFront `scripts/api/`가 이 JSON을 받아 `/docs/`의 "API 레퍼런스" 페이지로 생성한다.

| | |
|---|---|
| 경로 | `GET /v3/api-docs` — `SecurityFilterChain`에서 permitAll(토큰 불필요) |
| 노출 범위 | 게이트웨이·nginx가 `/v3`를 라우팅하지 않는다 → **클러스터 내부 전용** |
| 담기는 것 | `/api/org/**` 31개 오퍼레이션, 태그 6개(Members · Teams · Grants · Invitations · Me · Avatars) |
| 안 담기는 것 | `/internal/org/**`(`springdoc.paths-to-match` + `@Hidden`), 액추에이터, gRPC `PermissionService`(REST가 아니다) |

주석 규약: 컨트롤러에 `@Tag`, 엔드포인트마다 `@Operation(summary)`, 뜻이 안 드러나는 파라미터에 `@Parameter`, DTO 핵심 필드에 `@Schema(description, example)` — 전부 한국어 한 줄.

공통 오류는 `config/OpenApiConfig`의 `OperationCustomizer`가 붙인다: 401·403은 모든 오퍼레이션에, 404는 경로 변수를 받는 오퍼레이션에, 400은 본문을 받는 오퍼레이션에. 사유가 제각각인 409만 `@ConflictResponse("사유")`를 붙인 곳에 그 사유로 들어간다. 응답 스키마는 common-starter의 `{"error": 메시지}` 계약(`PlatformError`)이다.

> **400은 "본문을 받는 오퍼레이션" 규칙이지 완전한 목록이 아니다.** 엄밀히 따지면 모든 컨트롤러가 `Long.parseLong(jwt.getSubject())`로 호출자를 푼다 — `NumberFormatException`은 `IllegalArgumentException`이라 common-starter가 400으로 매핑하므로, 31개 오퍼레이션 전부가 이론상 400을 낼 수 있다. 그걸 다 적으면 아무 정보도 주지 않으므로, **읽는 사람이 잘못된 값을 보내서 실제로 400을 만들 수 있는 자리**에만 적는다. wiki·alm도 같은 규칙을 쓴다(2026-09-05 합의).

`springdoc.override-with-generic-response: false`인 이유: 켜 두면 `@RestControllerAdvice`가 다루는 예외가 전부 모든 오퍼레이션의 응답으로 복사돼, 목록 조회에도 404·409·503이 붙는다. 그러면 "이 엔드포인트가 실제로 내는 코드"라는 뜻이 사라진다.

`OpenApiDocsTest`가 게이트다 — 스펙이 200인지, 태그·요약 없는 오퍼레이션이 0개인지, 성공 응답이 빠진 곳이 없는지, 내부 경로가 새지 않는지, `bearerAuth`가 전역인지를 검증한다.

---

## search-service

위키 본문·제목·첨부 파일명을 대상으로 한 전문 검색을, **사용자가 볼 수 있는 스페이스로 한정해서** 돌려준다.

**RDB를 갖지 않는다.** 색인은 OpenSearch가 소유하고 원본은 wiki-backend가 소유한다 — 재색인으로 언제든 복구되는 파생 데이터뿐이라 별도 DB를 둘 이유가 없다.

### 검색 API — GraphQL

엔드포인트는 서비스 기준 `POST /graphql`. 게이트웨이가 `/api/search/**`를 `StripPrefix=2`로 넘기므로 **외부 경로는 `POST /api/search/graphql`** 이다. 인증 필수.

```graphql
type Query { search(input: SearchInput!): SearchResults! }

input SearchInput {
  query: String!
  spaceIds: [ID!]            # 접근 가능한 스페이스와 교집합 — 여기로 권한을 넓힐 수는 없다
  docTypes: [DocType!]
  includeDrafts: Boolean = false
  page: Int = 0
  size: Int = 20             # 상한 100 (넘기면 100으로 잘린다)
}

type SearchResults { total: Int!, tookMs: Int!, hits: [SearchHit!]! }

type SearchHit {
  id: ID!  docType: DocType!  spaceId: ID!  spaceKey: String!  spaceName: String!
  pageId: ID       # ATTACHMENT면 소속 페이지
  title: String    # PAGE
  filename: String # ATTACHMENT
  highlights: [String!]!  updatedAt: String  score: Float!
}

enum DocType { PAGE, ATTACHMENT }
```

계약의 정본은 `search-service/src/main/resources/graphql/schema.graphqls`다.

### 권한 필터 (fail-closed)

JWT `sub` → `userId` → org-service **`ListUserGrants(user_id)`** gRPC 호출.
`resource_type`은 **지정하지 않는다**(UNSPECIFIED = 전체) — 응답에서 GLOBAL 여부를 먼저 보고, 없으면 `SPACE` grant만 골라 스페이스 id 집합을 만든다. SPACE로 좁혀 물으면 GLOBAL 보유자를 판별할 수 없기 때문이다.
GLOBAL grant 보유자는 필터 없음, 아니면 `terms { space_id: [...] }`를 질의에 AND로 건다.

- **필터는 서버가 건다.** 클라이언트가 보낸 `spaceIds`는 접근 가능 집합과 **교집합**만 취한다(좁히기만 가능, 넓히기 불가).
- **org-service 불능 시 503을 전파한다**(fail-closed). 권한을 모르는 상태에서 빈 결과를 주면 "검색해도 안 나오네"로 조용히 오인되기 때문이다.
- grant 조회 캐시는 없다(요청당 gRPC 1회).

### 색인 — Redis Streams 소비

스트림 `platform:events:v1`, consumer group `search-service`. 처리 뒤에만 XACK한다.

| 이벤트 | 처리 |
|---|---|
| `PageCreated` / `PageUpdated` | gRPC로 본문 조달 → upsert |
| `PageDeleted` | 문서 삭제 + 그 페이지 첨부 `delete_by_query` |
| `SpaceUpdated` | `update_by_query`로 비정규화된 `space_name`·`space_key` 갱신 |
| `SpaceDeleted` | 두 인덱스 모두 `delete_by_query(space_id)` |
| `AttachmentAdded` / `AttachmentDeleted` | 첨부 문서 upsert / 삭제 |

- **멱등** — 문서 `_id`가 도메인 키라 같은 이벤트를 두 번 받아도 결과가 같다.
- **순서 역전 방어** — `version_type=external`, `version = occurred_at`(epoch millis). 늦게 도착한 오래된 이벤트는 OpenSearch가 `version_conflict`로 거부하며, 이는 정상 흐름으로 취급해 ACK한다.
- **재시도 횟수는 Redis PEL의 delivery count에서 읽는다.** 별도 상태로 세면 재기동에서 리셋돼 같은 이벤트가 영원히 재시도된다. 상한(기본 5) 초과 시 `platform:events:v1:dlq`로 옮기고 원본 XACK — 둘은 Lua 한 번으로 처리한다(갈리면 이벤트가 복제되거나 사라진다).
- **색인 실패는 WARN이 아니라 ERROR**로 남긴다. 화면이 멀쩡해서 티가 안 나는 종류의 고장이기 때문이다.

인덱스는 물리 `wiki-page-v1`·`wiki-attachment-v1`, 읽기·쓰기는 별칭 `wiki-page`·`wiki-attachment`로만 한다.

### 재색인 (관리자 REST)

GraphQL이 아니라 REST인 이유는 운영 조작이라 장애 대응 중 curl로 때릴 수 있어야 하기 때문이다. 권한은 org-service **GLOBAL ADMIN**.

| 외부 경로 | 내부 경로 | 동작 |
|---|---|---|
| `POST /api/search/admin/reindex` | `POST /admin/reindex` | 비동기 잡 시작 → `202` + jobId |
| `GET /api/search/admin/reindex/{jobId}` | `GET /admin/reindex/{jobId}` | 진행 상태 조회 |

새 인덱스 `wiki-page-v{n+1}` 생성 → wiki-backend gRPC 스트림으로 전량 색인 → **별칭 원자 스위치** → 구 인덱스는 남긴다(수동 삭제).
잡 상태는 메모리에 있다 — 재기동하면 잃지만, 다시 돌리면 되는 조작이라 수용한다.

> **알려진 갭**: 재색인 중 들어온 이벤트를 신·구 인덱스에 함께 쓰는 **dual-write가 없다.** 별칭 전환 시점 부근의 이벤트 창은 후속 과제다.

### 기동 시 확인

OpenSearch 연결과 인덱스 존재를 확인하고, **실패하면 기동을 중단한다**(fail-fast). 색인 불능인 채 소비자가 정상처럼 ACK하면 이벤트가 조용히 사라지기 때문이다.
부트스트랩은 별칭이 이미 있으면 v1으로 되돌리지 않는다 — 재색인으로 v2에 옮긴 별칭을 기동이 되감는 사고를 막는다.

---

## 빌드 · 실행

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'

.\gradlew.bat build                        # 전 모듈 빌드 + 테스트
.\gradlew.bat :org-service:test            # 모듈 단위 테스트
.\gradlew.bat :search-service:test         # 64개

.\gradlew.bat :org-service:bootRun         # :9130 / gRPC :9131
.\gradlew.bat :search-service:bootRun      # :9140  (OpenSearch 필요)

.\gradlew.bat :search-service:bootJar      # build/libs/app.jar (Dockerfile이 이 이름으로 복사)
```

`search-service` 테스트는 색인·소비 경로를 **Testcontainers**(Redis + nori OpenSearch)로 검증한다 — 페이크만으로 통과시키지 않는다. 도커가 필요하다.

주요 환경 변수(`application.yml` 기본값):

| 변수 | 기본값 | 용도 |
|---|---|---|
| `OPENSEARCH_URI` | `http://localhost:9200` | 색인 저장소 |
| `REDIS_HOST` / `REDIS_DB` | `localhost` / `0` | 이벤트 스트림 (**dev는 db1** — 발행자와 반드시 같아야 한다) |
| `ORG_GRPC_HOST` / `ORG_GRPC_PORT` | `localhost` / `9131` | 권한 필터 |
| `WIKI_GRPC_HOST` / `WIKI_GRPC_PORT` | `localhost` / `9111` | 본문·첨부 조달 |
| `EVENTS_ENABLED` / `EVENTS_MAX_RETRIES` | `true` / `5` | 소비 on-off, DLQ 이동 임계 |
| `AUTH_JWKS_URI` · `PLATFORM_ISSUER` · `PLATFORM_AUDIENCE` | auth-server 계약 | JWT 검증 |

> dev에서 Redis DB 번호가 발행자와 다르면 소비자는 **빈 스트림을 정상 상태로 오인**하고 검색 결과만 조용히 스테일해진다. `application-dev.yml`이 db1로 맞춰 둔 이유다.

---

## Docker 배포 동작

`Dockerfile`은 **런타임 전용**이다 — jar를 빌드하지 않고 `<module>/build/libs/app.jar`를 복사한다. 그래서 **빌드 컨텍스트가 repo 루트**여야 한다.

```powershell
.\gradlew.bat :search-service:bootJar
docker build -f search-service/Dockerfile -t search-service .   # context = repo 루트
```

컨테이너에서는 `docker` 프로필이 활성화되고, 이때:

- **Eureka에 등록하지 않는다**(`eureka.client.enabled: false`). 게이트웨이가 `ORG_SERVICE_URI`/`SEARCH_SERVICE_URI`로 컨테이너 DNS에 직결한다 — 이 env가 빠지면 해당 라우트가 통째로 503이다.
- 콘솔 로그를 **ECS JSON**으로 출력한다. Alloy가 stdout을 수집해 Loki로 보낸다(앱은 Loki를 모른다).

스택 전체 구성은 우산 repo(`chanho4702/infra-settings`)의 [infra/README.md](https://github.com/chanho4702/infra-settings/blob/main/infra/README.md) 참고.

---

## CI / 배포

`.github/workflows/ci.yml`:

- **PR·push 공통**: `./gradlew build`(전 모듈 컴파일 + 테스트). 이 게이트를 통과하지 못하면 어떤 이미지도 푸시되지 않는다.
- **`main` push**: GHCR 로그인 → `org-service`·`search-service` 이미지를 각각 빌드·푸시(`latest` + `sha-<커밋>`) → 서비스별로 `chanho4702/infra-settings`에 `repository_dispatch`(`event-type: deploy`) 발사.
- **`v*` 태그 push**: `publish-proto` 잡이 `common-proto`를 GitHub Packages에 발행. 태그 push에서는 이미지·배포 스텝이 돌지 않는다.

| 모듈 | GHCR 이미지 | 배포 태그 env | 컨테이너 |
|---|---|---|---|
| `org-service` | `ghcr.io/chanho4702/org-service` | `ORG_TAG` | `platform-org` |
| `search-service` | `ghcr.io/chanho4702/search-service` | `SEARCH_TAG` | `platform-search` |

dispatch를 서비스마다 따로 쏘는 이유는 배포 워크플로의 `concurrency` group이 `deploy-<service>`라 두 배포가 서로를 취소하지 않고 나란히 돌기 때문이다.

배포 쪽은 `--no-deps`로 해당 서비스만 pull + 재기동하고 컨테이너가 healthy가 될 때까지(최대 2분) 기다린다.
**첫 배포는 CI 이미지 푸시가 선행돼야 한다** — 배포의 첫 동작이 `docker compose pull`이라 GHCR에 이미지가 없으면 실패한다. 또한 `--no-deps`는 의존(OpenSearch)을 띄우지 않으므로 search-service 최초 기동은 의존을 포함해 올려야 한다.
