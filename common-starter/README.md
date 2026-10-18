# common-starter

리소스 서버 5곳(wiki·alm·board·org·search)에 복제돼 있던 것을 한 곳으로(S-02, 2026-08-30).

| 제공 | 내용 |
|---|---|
| `com.platform.common.security.PlatformResourceServerAutoConfiguration` | `JwtDecoder`(JWKS + issuer + **audience** 검증, JWKS 조회 연결 2s·읽기 3s 타임아웃) · `JwtAuthenticationConverter`(`roles` → `ROLE_*`). 서비스가 같은 타입의 빈을 두면 물러난다 |
| `com.platform.common.error.*` | `NotFoundException` 404 · `ForbiddenException` 403 · `ConflictException` 409 · `ServiceUnavailableException` 503 · `BadRequestException` 400(0.17.0) |
| `com.platform.common.web.PlatformApiExceptionHandler` | 위 예외 + 검증 실패·본문 파싱 실패·타입 불일치·파라미터 누락 → 400, 낙관적 락 → 409, `AccessDeniedException` → 403. 본문은 플랫폼 계약 `{"error": "메시지"}` |
| `com.platform.common.web.PlatformErrorController` | `/error`(미처리 예외·405·415·`@ResponseStatus` 등)도 `{"error"}`. 5xx는 고정 문구, 원인은 로그로(0.17.0) |
| `com.platform.common.security.PlatformSecurityErrorHandlers` | 보안 체인 401/403에 `{"error"}` 본문을 싣는 엔트리포인트·거부 처리기 — 서비스가 체인에 꽂는다(0.17.0) |
| `com.platform.common.security.InternalTokenFilter` | `/internal/**` 공유 토큰 게이트 단일 구현 — 헤더명 지정(기본 `X-Internal-Token`)·trim·상수시간·fail-closed. **빈으로 등록하지 말 것**(0.17.0) |
| `com.platform.common.grpc.*` | 서비스 간 gRPC 토큰 인터셉터 빈 `platformGrpcClientInterceptor`(채널에 `.intercept`)·`platformGrpcServerInterceptor`(off/monitor/enforce). env `PLATFORM_GRPC_TOKEN`·`PLATFORM_GRPC_AUTH_MODE`(0.17.0) |

서비스별 예외(예: wiki `MoveImpactException`)는 서비스 안의 `@RestControllerAdvice`에 `@Order(0)`으로 두면
먼저 잡힌다 — 공용 핸들러는 `Ordered.LOWEST_PRECEDENCE`다.

**`IllegalArgumentException` 문구(0.17.0).** 한글이 들어간 문구(서비스가 쓴 것)만 그대로 내보내고, 라이브러리 원문
(`No enum constant com.platform…`, `For input string`)은 "요청 값이 올바르지 않습니다"로 바꾼다. 사용자에게 보일 400을
의도적으로 던질 때는 `BadRequestException`을 쓴다.

필요한 설정: `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, `platform.jwt.issuer`, `platform.jwt.audience`.
**0.17.0부터 `jwk-set-uri`가 있는데 issuer·audience가 비면 기동이 실패한다**(예전엔 구성이 조용히 빠지고 audience
검증 없는 Boot 기본 디코더가 떴다). 의도적으로 플랫폼 JWT 검증을 빼는 인스턴스(위키 docs 프로필)는
`spring.autoconfigure.exclude`에 `PlatformResourceServerAutoConfiguration`을 넣는다. 선택: `platform.jwt.jwks-connect-timeout`(2s)·
`platform.jwt.jwks-read-timeout`(3s), `platform.web.error-controller.enabled`(true).
`SecurityFilterChain`(어떤 경로를 열지)은 서비스가 계속 자기 것을 둔다 — 그건 서비스마다 다르다.

발행: common-proto와 같은 `v*` 태그로 GitHub Packages에 같은 버전으로 올라간다. common-proto는 0.17.0부터 `grpc-bom`을
`api platform`으로 노출한다 — 소비 서비스의 `grpc-netty-shaded`·`grpc-inprocess`는 버전 없이 선언해도 stub과 같은 버전이 된다.
