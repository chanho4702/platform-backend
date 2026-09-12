# 통합 검색 설치 옵션 — `SEARCH_MODE` (2026-09-12) · **설계안 초안**

사용자: "통합 검색 기능은 옵션으로 켜고 끌 수 있게."

메일 설치 옵션(`MAIL_MODE`, 2026-09-07)과 **같은 패턴**으로 만든다: 설치 시점 env 하나 + compose `profiles:` + `*-up.ps1` 스크립트 + 모드별 smoke 관문 + README 표.

> 2026-09-12 리드 결정 반영(§9): 기본 `lite` + 기존 배포 env 2곳에 `SEARCH_MODE=opensearch` 명시 · A안(게이트웨이 파생) · 라이트 모드 안내 한 줄 노출. 사용자 지시: "통합 검색 기능은 옵션으로 켜고 끌 수 있게".

---

## 0. 실측 (2026-09-12 현재 main)

**이미 있는 것 — "꺼진 배포"는 코드상 거의 다 준비돼 있다.**

| 지점 | 상태 |
|---|---|
| 라이트 검색 본체 | `wiki-backend`의 `search/LiteSearchService`·`LiteSearchController`가 **search-service와 같은 GraphQL 스키마**를 Postgres로 구현한다. `graphql/search.graphqls`가 `schema.graphqls`와 동일 계약 |
| 라이트 검색 인덱스 | `V18__search_trgm.sql` — `pg_trgm` GIN(제목·본문·첨부 파일명). **최선 노력**: 권한 없어 실패하면 인덱스 없이 순차 스캔으로 계속 간다 |
| 게이트웨이 전환점 | `gateway-server/src/main/resources/application.yml:172-177` 라우트 `search`, `uri: ${SEARCH_SERVICE_URI:lb://search-service}` + `StripPrefix=2`. 주석(155-159행)이 이미 "OpenSearch 없는 배포는 이 값을 `http://wiki-backend:9110`으로" 라고 적어 두었다 |
| 헬스 대시보드 | `HealthAggregator.isProbed()` — 타깃 URL이 비면 그 컴포넌트는 표에서 **행 자체가 사라진다**. `HEALTH_URL_OPENSEARCH`는 이미 기본값이 빈 문자열 |
| 프론트 색인 관리 화면 | `SearchAdminPage`가 403/실패를 모두 "권한 없음" 빈 상태로 처리한다 — 라이트 모드의 404에도 죽지 않는다 |
| 프론트 검색 화면 | `wikiApi.searchContent()`는 `/api/search/graphql` 한 곳만 부른다. 계약이 같으므로 **라이트 모드에서 무수정으로 동작한다** |

**없는 것 — 이번 작업의 실제 범위.**

| 지점 | 문제 |
|---|---|
| compose | `opensearch`(147행)·`search-service`(634행)에 `profiles:`가 **없다** → 맨 `docker compose up -d`에 항상 뜬다. 실측 회수 메모리 opensearch 1.3GiB + search 436MiB |
| 배포 | `infra/.github/workflows/deploy.yml`이 `up -d --no-build --no-deps --scale search-service=1 search-service` 후 120초 healthy 폴링. **운영자가 검색을 껐어도 platform-backend main 푸시 한 번이면 search-service를 강제로 되살리고**, `--no-deps`라 opensearch 없이 뜨므로 healthy가 되지 못해 배포 잡이 FAIL한다. ← **가장 치명적** |
| smoke | `scripts/smoke-stack.ps1:115-117`이 `opensearch`·`search-service`의 헬스체크 정의 존재를 **무조건** 요구, 159-164행이 `SEARCH_SERVICE_URI == http://search-service:9140`을 **무조건** 요구, 280-297행이 search-service env 배선을 무조건 요구 |
| 전환 스크립트 | 없다(메일은 `scripts/mail-up.ps1`) |
| 프론트 기능 플래그 | **없다.** 플랫폼 어디에도 런타임 기능 플래그 엔드포인트가 없다. wiki-front의 인스턴스 분기는 전부 빌드타임 `VITE_*`(`VITE_SEARCH_API_PREFIX` 등)이라 **설치 옵션에 쓸 수 없다** — 이미지는 미리 빌드돼 GHCR에서 내려온다 |
| 메뉴 | `WikiTopBar.tsx:152` "검색 색인 관리" 항목이 **항상 보인다** → 라이트 모드에서 죽은 화면으로 안내한다. 이번 작업의 유일한 눈에 보이는 결함 |
| 잔재 | compose 651행 `ALM_GRPC_HOST: alm-backend` — main의 search-service는 이 env를 **읽지 않는다**(미머지 브랜치의 흔적) |

**소비자 관계 정리**

- **wiki-front** → `/api/search/graphql`(전역 검색·`SearchPage`) + `/api/search/admin/reindex*`(관리 화면). 여기가 `/api/search/**`의 **유일한 호출자**다.
- **alm-front / alm-backend** → `/api/search/**`를 **전혀 부르지 않는다**. ALM 검색은 AQL(`search/aql/*`, `AqlSpecification` → JPA Specification)과 `IssueSearchController`로 **100% 자기 DB**다. 검색 옵션과 무관하게 항상 동작한다.
- **이벤트**: `wiki-backend`·`alm-backend` 둘 다 `RedisStreamEventPublisher`로 `platform:events:v1`에 `XADD MAXLEN ~100000`. **소비자는 search-service 하나뿐**. 발행은 `platform.events.enabled`로만 꺼지고 소비자 유무와 무관하다.

---

## 1. 핵심 결정: "끈다"는 **검색 없음이 아니라 라이트 검색**이다

위키에서 검색을 통째로 없애면 제품이 망가진다. 그리고 그럴 필요도 없다 — 라이트 검색이 이미 같은 계약으로 구현돼 있다. 그래서 이 옵션이 켜고 끄는 것은 **"통합(cross-app)·형태소 분석 검색"**이지 검색 자체가 아니다.

| | 라이트 (`lite`) | OpenSearch (`opensearch` / `external`) |
|---|---|---|
| 구현 | wiki-backend + Postgres `pg_trgm` | search-service + OpenSearch(Nori) |
| 대상 | **위키만**(페이지·폴더·블로그·첨부) | 위키 (+ 향후 ALM 이슈 → §7) |
| 매칭 | `lower(col) LIKE '%q%'` 부분 문자열 | 한국어 형태소 분석 + 관련도 스코어 |
| `total` | 후보창 500건 초과 시 `totalExact=false` | 정확 |
| 재색인 | 개념 없음(`/admin/reindex` 404) | 있음 |
| 추가 메모리 | 0 | ~1.75GiB |

이 차이를 **관리 화면에 그대로 적어야 한다.** 안 적으면 "검색이 이상하다" 버그로 되돌아온다.

---

## 2. 옵션 이름과 모드

**`SEARCH_MODE` ∈ `lite`(기본) | `opensearch` | `external`**

`SEARCH_ENABLED` 같은 불리언을 쓰지 않는 이유: 이미 세 번째 현실적 케이스(고객사가 이미 굴리는 ES/OpenSearch 클러스터에 붙이기)가 있고, 메일이 `none/external/relay/full`로 같은 문제를 겪었다.

| SEARCH_MODE | 컨테이너(profile) | 게이트웨이 라우트 대상 | 용도 |
|---|---|---|---|
| `lite`(기본) | 없음 | `http://wiki-backend:9110` | 소규모 온프렘. 메모리 1.75GiB 절약, 위키 자체 검색만 |
| `opensearch` | `opensearch` + `search-service`(profile `search`) | `http://search-service:9140` | 기본 풀스택. 한국어 형태소 + 통합 검색 |
| `external` | `search-service`만(profile `search`, `--no-deps`) | `http://search-service:9140` | 고객사 기존 OpenSearch/ES 클러스터 재사용. `OPENSEARCH_URI`를 그 주소로 |

`dev`는 별도 모드로 두지 않는다 — 메일과 달리 "캡처 전용" 같은 개발 전용 형태가 없다.

### 2.1 스위치는 **하나**여야 한다

게이트웨이 application.yml 157행이 명시한 규칙 그대로다: *"스위치는 이 환경변수 하나뿐이고, 두 곳에 두면 반쪽만 바뀐 배포가 생긴다."* `SEARCH_MODE`를 도입하면서 `SEARCH_SERVICE_URI`·`HEALTH_URL_SEARCH_SERVICE`·`HEALTH_URL_OPENSEARCH`를 각각 손으로 맞추게 하면 그 규칙을 우리가 깬다.

**A안 (권장) — 게이트웨이가 `SEARCH_MODE`에서 파생한다.**
- compose가 게이트웨이에 `SEARCH_MODE`를 넘긴다.
- 게이트웨이가 라우트 대상을 파생: `lite` → `WIKI_SERVICE_URI`(없으면 `lb://wiki-backend`), 그 외 → `http://search-service:9140`. `SEARCH_SERVICE_URI`가 **명시되면 그것이 이긴다**(탈출구로 남긴다).
- `HealthAggregator.isProbed()`가 `SEARCH_MODE`를 함께 본다: `lite`면 `search-service`·`opensearch` 두 행을 모두 뺀다. `external`이면 `opensearch` 행만 뺀다(우리 것이 아니다).
- 비용: 게이트웨이에 작은 설정 클래스 + `isProbed` 조건 + 테스트. 대략 60줄.

**B안 (대안) — env를 여럿 두고 smoke가 정합성을 강제한다.** 메일이 `MAIL_SEED_HOST`를 모드와 대조하는 방식(`smoke-stack.ps1:367-411`)과 동일. 코드 변경 0, 대신 운영자가 3개를 맞춰야 하고 드리프트는 smoke를 돌려야만 잡힌다.

→ **A안 권장.** 라우트 대상이 틀리면 검색이 조용히 통째로 죽는 지점이라, 사후 검출보다 구조적 차단이 맞다.

### 2.2 기본값을 `lite`로 두는 것의 위험

메일은 기본 `none`이다. 같은 기준이면 검색도 기본 `lite`가 맞다(신규 설치의 메모리 부담을 기본에서 뺀다). **그런데 지금 돌고 있는 배포는 opensearch 모드다.** 기본값만 바꾸고 env를 안 쓰면 **다음 배포에서 운영 검색이 조용히 라이트로 내려앉는다.**

→ 같은 커밋에서 반드시 함께: `infra/keycloak/.env`와 `C:\deploy\platform.env`에 `SEARCH_MODE=opensearch` 명시 + `.env.example`에 `SEARCH_MODE=lite` + smoke의 "모드와 실제 컨테이너 일치" 관문. 이 셋 중 하나라도 빠지면 배포하지 않는다.

---

## 3. compose

```yaml
  opensearch:
    profiles: ["search"]        # ← 추가
    ...

  search-service:
    profiles: ["search"]        # ← 추가
    depends_on:
      redis: { condition: service_healthy }
      opensearch: { condition: service_healthy }   # 유지
    environment:
      OPENSEARCH_URI: ${OPENSEARCH_URI:-http://opensearch:9200}   # external이 덮어쓴다
      # ALM_GRPC_HOST 제거 — main의 search-service는 읽지 않는다(§7)
```

**두 컨테이너를 같은 단일 프로필 `search`에 넣는다.** 프로필을 둘로 쪼개면 `search-service`(profile X) → `depends_on: opensearch`(profile Y) 가 프로필 해석 시 깨진다. `external`은 프로필을 켠 채 `--no-deps`로 search-service만 띄워 해결한다 — `mail-up.ps1:163`의 `up -d --no-deps <service>`와 정확히 같은 수법이다.

게이트웨이 env에 `SEARCH_MODE: ${SEARCH_MODE:-lite}` 추가.

`.env.example`:
```
# 통합 검색 설치 옵션. lite=위키 자체 검색(추가 컨테이너 없음) / opensearch=풀스택 / external=기존 클러스터
SEARCH_MODE=lite
# external 모드에서만: 고객사 OpenSearch/ES 주소
# OPENSEARCH_URI=http://search.internal:9200
```

---

## 4. `scripts/search-up.ps1` (신규 · `mail-up.ps1` 복제)

- `-Mode lite|opensearch|external`. 생략하면 `C:\deploy\platform.env` → 없으면 `infra\keycloak\.env`의 `SEARCH_MODE`를 따른다(`mail-up.ps1:46-49, 99-114`와 동일).
- 전환 시 **다른 모드의 컨테이너를 먼저 정리**한다: `docker compose rm --stop --force`(`mail-up.ps1:138-142`).
  - `lite` → `opensearch`, `search-service` 둘 다 제거
  - `external` → `opensearch`만 제거, `--profile search up -d --no-deps search-service`
  - `opensearch` → `--profile search up -d opensearch search-service`
- **메일에 없는 두 단계가 추가로 필요하다:**
  1. **게이트웨이 재기동**: 라우트 대상이 env에서 오므로 `up -d gateway`를 해야 새 모드가 반영된다. 메일은 런타임 정본이 DB라 필요 없었다.
  2. **`lite` → `opensearch` 전환 후 전량 재색인 안내/실행**: `POST /api/search/admin/reindex`. 이유는 §8 위험 3.
- UTF-8 **BOM**으로 저장한다(확정 결정).

---

## 5. 배포·CI·smoke

**deploy.yml (필수, 이거 없으면 옵션이 성립하지 않는다)** — `infra/.github/workflows/deploy.yml` "Deploy backend" 단계가 `service == 'search-service'`일 때 `platform.env`의 `SEARCH_MODE`를 읽어 `lite`면 **스킵하고 성공 처리**한다. 지금은 무조건 `up -d --no-deps search-service` 후 120초 healthy 폴링 → 꺼진 설치에서 반드시 FAIL한다.

**CI** — `platform-backend/.github/workflows/ci.yml`은 그대로 둔다. 안 쓰는 이미지를 GHCR에 올리는 비용은 없고, 모드를 켜는 순간 최신 이미지가 이미 있어야 한다. 바뀌는 건 dispatch 이후의 배포 단계뿐이다.

**smoke-stack.ps1** — 무조건 검사 3곳을 모드 인지로 바꾸고, 메일(`641-676`)과 같은 라이브 관문을 추가한다.

| 현재 | 바꿀 것 |
|---|---|
| `115-117` opensearch·search-service 헬스체크 정의 필수 | `SEARCH_MODE != lite`일 때만 |
| `159-164` `SEARCH_SERVICE_URI == http://search-service:9140` 필수 | A안 채택 시 이 검사는 **삭제**하고 "게이트웨이에 `SEARCH_MODE`가 전달되는가"로 대체 |
| `280-297` search-service env 배선 | `SEARCH_MODE != lite`일 때만. `ALM_GRPC_HOST` 기대 제거 |
| (없음) 라이브 관문 | `opensearch`/`external` → 해당 컨테이너 running+healthy(`-RequireLive` 아니면 SKIP). `lite` → 두 컨테이너 **부재** 확인(메일의 `$strays` 패턴) |
| `565-569` 401 검사 | 그대로 — 이미 다운스트림 없이도 성립한다고 주석에 적혀 있다 |

**dev 스크립트** — `scripts/dev-up.ps1:25`의 맨 `docker compose up -d`는 프로필이 붙는 순간 자동으로 검색을 뺀다(추가 작업 없음). `SEARCH_MODE=opensearch`면 `--profile search`를 붙이도록 한 줄 분기. `dev-up-local.ps1`은 이미 "search-service 미기동이면 그것만 실패하고 나머지는 정상"이라 문서만 갱신.

**nginx** — 변경 없음. `/api/search/**`는 범용 `location`으로 게이트웨이에 넘어가고 nginx는 검색을 모른다.

---

## 6. 프론트 기능 플래그

### 6.1 무엇을 숨겨야 하나

라이트 모드에서 실제로 잘못되는 것은 **하나뿐**이다: `WikiTopBar.tsx:152` "검색 색인 관리" 메뉴가 죽은 화면(404 → "권한 없음")으로 안내한다. 검색 화면 자체·전역 검색 필드·결과 목록은 계약이 같아 **무수정으로 동작한다**.

### 6.2 전달 방식 — `GET /api/platform/features` (신규, 게이트웨이 소유)

```json
{ "search": { "mode": "lite", "reindex": false, "scopes": ["WIKI"] } }
```

- **게이트웨이가 소유하는 이유**: 모드를 아는 유일한 컴포넌트이고, 이미 `/api/platform/health`를 같은 논리(단일 진입점만이 전 컴포넌트를 안다)로 직접 응답한다.
- **권한**: `/api/platform/**`는 지금 `PlatformController`의 `AdminGate`로 전역 관리자만 통과한다. features는 **메뉴 표시 판단**이라 로그인한 모든 사용자가 읽어야 한다 → `AdminGate`를 태우지 **않는** 별도 컨트롤러로 둔다(보안 체인의 `anyExchange().authenticated()`는 그대로 받는다). PAT는 `PatScopeRules`가 `/api/platform` 전면 거부 중이므로 그대로 둔다.
- **프론트는 실패를 `lite`로 읽는다.** 이게 설계의 핵심이다:
  - 공개 문서 인스턴스(`/docs/`)는 nginx가 docs wiki-backend로 **직접** 프록시해 게이트웨이를 안 거친다 → features를 못 받는다 → 자동으로 lite. 정확한 동작이다(docs에는 관리 메뉴가 없다).
  - 구버전 게이트웨이(404)와도 호환된다.
  - 목업 모드(`USE_BACKEND=false`)도 자동으로 lite.
- 빌드타임 `VITE_*`는 **쓰지 않는다.** 이미지가 GHCR에서 내려오는 설치형이라 빌드타임 플래그는 설치 옵션이 될 수 없다.

### 6.3 프론트 변경

- `wiki-front`: `store/platformApi.ts`(신규, 세션 1회 캐시) → `WikiTopBar`에서 `features.search.reindex`가 false면 메뉴 항목 제거. `SearchPage`에 라이트 모드 안내 한 줄(선택 — §9 결정 대기).
- `alm-front`: **이번 범위에서 변경 없음.** ALM은 `/api/search/**`를 안 부른다. §7이 진행될 때 소비한다.

---

## 7. 미머지 브랜치 — **재이식은 이 작업에 필요 없다**

리드 질문에 대한 답: **main만으로 충분하다.** 두 브랜치는 "검색을 옵션으로"와 무관한 별개 기능(ALM 이슈 통합 색인)이고, 지금 재이식하면 옵션 작업의 위험만 키운다.

실측(`origin/main` 기준, 병합 기준점 `a5046ce` — main은 그 뒤 **32 커밋** 전진):

| 브랜치 | 3-dot diff | 내용 |
|---|---|---|
| `origin/feat/wiki-global-search` | 5파일 +53/-3 | 검색 결과에 `pageType` 노출 |
| `origin/feat/wave-d-alm-search` | 36파일 +979/-97 | 위 + `IssueDoc`·`AlmDocuments`·`GrpcAlmContentClient`·`AlmEventIndexer`·`DocType.ISSUE`·`projectIds`·ALM 재색인 (`wiki-global-search`의 자손) |

**재이식이 비싼 이유 3가지**

1. **proto는 이미 main에 있다.** 브랜치의 `platform/alm/v1/alm.proto`와 `events/v1/events.proto` 확장은 main 커밋 `22e1ec5`로 **바이트 동일하게** 이미 올라가 있다(`git diff`가 비어 있다). 재이식 가치 0.
2. **`OpenSearchQueryService.java`가 지뢰다.** 브랜치가 잘린 뒤 main에서 **6개 커밋**이 같은 파일을 고쳤다(관련도/날짜 정렬·라벨 필터·작성자/날짜 필터·W18 페이지 제한 후필터·pageType·common-starter 이관). `git merge-tree` 드라이런에서 실제 충돌 마커가 **4파일**(`OpenSearchQueryService` 6훅, `SearchHit`, `schema.graphqls`, `GraphQlSearchIntegrationTest` 2훅)에 뜬다. 작은 쪽 브랜치만 병합해도 같은 4파일에서 7훅이 뜬다.
3. **common-starter 이전 코드다.** 브랜치는 S-01/S-02(2026-08-30) 이전이라 search-service 로컬 `SecurityConfig`·`AudienceValidator`·예외 클래스들을 아직 들고 있다. 이 파일들은 텍스트 충돌 없이 **깨끗하게 병합되면서** 플랫폼이 일부러 제거한 복제분을 되살린다 — 확정 결정("서비스에 다시 복제하지 않는다") 위반이 조용히 들어온다.

**권고**: 브랜치는 **참조용으로만** 남긴다. ALM 통합 검색이 필요해지면 병합·체리픽이 아니라 **현재 main 위에 새로 구현**하고, 브랜치의 부가적 부분(`IssueDoc`·`AlmDocuments`·`AlmEventIndexer`·스키마 추가분 — 이쪽은 신규 파일이라 충돌 없음)만 설계 참고로 읽는다. 그때 compose 651행의 `ALM_GRPC_HOST`가 되살아난다.

부수 정리: 그 전까지 `ALM_GRPC_HOST`는 **읽는 코드가 없는 죽은 설정**이므로 이번에 제거하고, 되살릴 자리를 주석으로 남긴다.

**옵션과 ALM의 관계 (명문화 필요)**: 통합(cross-app) 검색은 **`opensearch` 모드 전용 능력**이다. `lite`에서는 각 앱이 자기 것만 찾는다(위키=라이트 GraphQL, ALM=AQL). 오늘 이미 사실상 그렇고, 이 문서가 그것을 계약으로 굳힌다.

---

## 8. 위험

1. **기본값 플립으로 운영 검색이 조용히 강등** — §2.2. 완화: 같은 커밋에서 `.env` 2개 명시 + smoke 관문.
2. **배포가 꺼진 검색을 되살리고 FAIL** — §5 deploy.yml 게이트. **이 항목 없이는 옵션이 성립하지 않는다.**
3. **`lite` → `opensearch` 전환 시 색인 공백** — 검색이 꺼져 있어도 wiki·alm은 계속 `XADD MAXLEN ~100000`한다(발행은 소비자와 무관). search-service의 컨슈머 그룹은 `ReadOffset.from("0-0")`으로 만들어져 **남아 있는 스트림은 재생한다**. 그러나 10만 건을 넘겨 트림된 구간은 영구 유실이다. → **전환 후 전량 재색인을 필수 절차로 문서화하고 `search-up.ps1`이 안내/실행**한다.
4. **부팅 실패 여부** — 게이트웨이는 `depends_on`에 search-service를 두지 않는다(의도적). 라이트 모드에서 `/api/search/**`는 wiki-backend로 정상 200이다. `external` 모드에서 고객사 클러스터가 안 뜨면 search-service만 실패하고 코어는 무영향(설계 §11). **확인 필요**: `OpenSearchIndexBootstrap`이 OpenSearch 부재 시 부팅을 막는지 — 막는다면 `external` 모드의 오진단 위험이라 재시도로 바꾼다.
5. **결과 품질 차이를 사용자에게 알리지 않으면 버그로 돌아온다** — 라이트는 형태소 분석 없음·부분 문자열·위키만·500건 초과 시 `totalExact=false`. 관리 화면과 README에 명시.
6. **`pg_trgm` 부재** — V18은 최선 노력이라 확장 권한이 없으면 인덱스 없이 순차 스캔이다. 문서 수만 건 규모에서 라이트는 느려진다. 모드 선택 가이드에 규모 기준을 적는다.
7. **모드 전환에 게이트웨이 재기동이 필요** — 메일과 다른 점. 스크립트가 대신하지만 손으로 `.env`만 고친 운영자는 아무 변화도 못 본다. README에 굵게.

---

## 9. 결정 (2026-09-12 확정)

1. **기본값 `lite`** — 메일 `none`과 같은 기준. 단 현 배포가 opensearch이므로 `infra/keycloak/.env`와 `C:\deploy\platform.env`에 `SEARCH_MODE=opensearch`를 **게이트웨이보다 먼저** 넣고 배포한다(순서: infra → gateway).
2. **A안** — 게이트웨이가 `SEARCH_MODE`에서 라우트 대상·헬스 프로브 행을 파생한다. `SEARCH_SERVICE_URI` 명시는 탈출구로 유지.
3. **라이트 모드 안내 노출** — 검색 화면 상단에 "이 설치는 위키 자체 검색입니다(통합 검색 옵션 꺼짐)" 한 줄. 관리 메뉴 "검색 색인 관리"는 숨긴다.

---

## 10. 리포별 작업 목록

| # | 리포 | 롤 | 작업 | 크기 |
|---|---|---|---|---|
| S1 | `infra` | 운영 | compose: `opensearch`·`search-service`에 `profiles: ["search"]`, 게이트웨이에 `SEARCH_MODE`, `ALM_GRPC_HOST` 제거, `.env.example` | S |
| S2 | `infra` | 운영 | `scripts/search-up.ps1` 신규(모드 전환·타 모드 정리·게이트웨이 재기동·재색인 안내). UTF-8 BOM | M |
| S3 | `infra` | 운영 | **`deploy.yml`에 search-service 스킵 게이트** — 최우선 | S |
| S4 | `infra` | 운영 | `smoke-stack.ps1` 모드 인지 전환(무조건 검사 3곳) + 라이브 관문 신규 | M |
| S5 | `infra` | 운영 | `README.md` "설치 옵션 — 검색" 절(메일 표와 같은 형식) + 모드 선택 가이드 + 전환 절차 | S |
| S6 | `infra` | 운영 | `dev-up.ps1` 모드 분기 한 줄, `dev-up-local.ps1` 주석 갱신 | XS |
| B1 | `gateway-server` | 백엔드 | `SEARCH_MODE`에서 라우트 대상 파생(A안), `SEARCH_SERVICE_URI`는 명시 시 우선하는 탈출구로 유지 + 테스트 | S |
| B2 | `gateway-server` | 백엔드 | `HealthAggregator.isProbed()`가 모드를 반영 — `lite`면 두 행, `external`이면 opensearch 행 제거 + 테스트 | S |
| B3 | `gateway-server` | 백엔드 | `GET /api/platform/features` 신규(인증만, AdminGate 없음, PAT 거부 유지) + 테스트 | S |
| B4 | `platform-backend` | 백엔드 | `OpenSearchIndexBootstrap`이 OpenSearch 부재로 부팅을 막는지 확인, 막으면 재시도로(위험 4) | S |
| F1 | `wiki-front` | 프론트 | `platformApi.ts` 신규(세션 캐시·실패 시 lite) + `WikiTopBar` "검색 색인 관리" 조건부 + 테스트 | S |
| F2 | `wiki-front` | 프론트 | (§9-3 결정 시) `SearchPage` 라이트 모드 안내 | XS |
| D1 | `wiki-backend` | 백엔드 | 변경 없음. 라이트 검색·V18은 이미 있다 | — |
| D2 | `alm-front`/`alm-backend` | — | 변경 없음(§7) | — |

**순서**: S3 → (S1 ∥ B1 ∥ B2) → S2 → B3 → F1 → S4 → S5. S3를 먼저 넣는 이유는 프로필을 붙이는 순간 다음 platform-backend 푸시가 배포를 깨기 때문이다.

**검증**: `search-up.ps1 -Mode lite` → 두 컨테이너 부재 확인 → 위키 전역 검색으로 실제 결과가 나오는지 → 관리 메뉴가 사라졌는지 → `-Mode opensearch` 복귀 → 재색인 → 같은 질의로 결과 비교. `smoke-stack.ps1 -RequireLive`를 두 모드 각각에서 통과시킨다.

---

## 11. 확정 결정 반영

이 작업이 끝나면 루트 `CLAUDE.md`·`AGENTS.md`의 "확정 설계 결정"에 한 줄 추가한다:

> **통합 검색은 설치 옵션(`SEARCH_MODE=lite|opensearch|external`, 기본 `lite`)**: 꺼진 배포에서는 게이트웨이 `/api/search/**`가 wiki-backend의 라이트 검색(같은 GraphQL 계약, Postgres `pg_trgm`)으로 간다. 프론트는 계약이 같아 무수정이며, 색인 관리 메뉴만 `/api/platform/features`로 숨긴다. cross-app(ALM) 검색은 `opensearch` 모드 전용 능력이다.
