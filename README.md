# platform-backend

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
| `platform.org.v1` | `PermissionService` — `CheckPermission` · `ListUserGrants` · `CreateGrant` · `RevokeGrant` |
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

> ⚠️ **기본값이 실행 방식에 따라 다르다.** `application.yml`은 `${PLATFORM_BOOTSTRAP_ADMIN_ID:}` — 즉 **코드 기본값은 빈 값이고, 비어 있으면 `BootstrapAdminSeeder`가 시딩을 건너뛴다.** `gradlew :org-service:bootRun`으로 직접 띄우면 아무도 자동으로 관리자가 되지 않는다.
> 반면 **compose는 `${PLATFORM_BOOTSTRAP_ADMIN_ID:-1}`로 1을 주입**하므로 컨테이너 스택에서는 사용자 1이 재기동마다 GLOBAL ADMIN으로 복구된다. 운영 배포 전 `.env`에 실제 관리자 id를 명시하거나 빈 값으로 두어 비활성화할 것.

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
