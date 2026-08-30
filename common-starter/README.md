# common-starter

리소스 서버 5곳(wiki·alm·board·org·search)에 복제돼 있던 것을 한 곳으로(S-02, 2026-08-30).

| 제공 | 내용 |
|---|---|
| `com.platform.common.security.PlatformResourceServerAutoConfiguration` | `JwtDecoder`(JWKS + issuer + **audience** 검증) · `JwtAuthenticationConverter`(`roles` → `ROLE_*`). 서비스가 같은 타입의 빈을 두면 물러난다 |
| `com.platform.common.error.*` | `NotFoundException` 404 · `ForbiddenException` 403 · `ConflictException` 409 · `ServiceUnavailableException` 503 |
| `com.platform.common.web.PlatformApiExceptionHandler` | 위 예외 + `IllegalArgumentException`·검증 실패·본문 파싱 실패 → 400. 본문은 플랫폼 계약 `{"error": "메시지"}` |

서비스별 예외(예: wiki `MoveImpactException`)는 서비스 안의 `@RestControllerAdvice`에 `@Order(0)`으로 두면
먼저 잡힌다 — 공용 핸들러는 `Ordered.LOWEST_PRECEDENCE`다.

필요한 설정: `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, `platform.jwt.issuer`, `platform.jwt.audience`.
`SecurityFilterChain`(어떤 경로를 열지)은 서비스가 계속 자기 것을 둔다 — 그건 서비스마다 다르다.

발행: common-proto와 같은 `v*` 태그로 GitHub Packages에 같은 버전으로 올라간다.
