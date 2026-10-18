package com.platform.orgservice.authsync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * auth-server 계정 상태 내부 API 클라이언트(P-02) — {@code PUT /internal/users/{id}/status {"active": bool}}.
 *
 * <p>인증은 auth-server가 이미 org 초대 API에 쓰는 것과 같은 공유 토큰({@code ORG_INTERNAL_TOKEN},
 * 헤더 {@code X-Internal-Token})이다 — 두 서비스 모두 이 env를 이미 받고 있어 배포 배선이 늘지 않는다.
 *
 * <p>auth-server 주소는 {@code platform.org.auth-sync.uri}(env {@code AUTH_SERVICE_URI}). 비어 있으면 JWKS 주소
 * ({@code AUTH_JWKS_URI}, 이미 auth-server를 가리킨다)의 오리진을 쓴다 — compose를 고치지 않아도 동작하게.
 *
 * <p>실패는 예외로 올리지 않고 결과로 돌려준다 — 워커가 다음 주기에 다시 보낸다.
 */
@Component
@Slf4j
public class AuthAccountClient {

    public enum Result { APPLIED, USER_NOT_FOUND, FAILED, NOT_CONFIGURED }

    private static final String HEADER = "X-Internal-Token";
    /** auth-server InternalUserStatusController의 404 본문 {@code "code":"user_not_found"}와 맞춘 계약. */
    private static final java.util.regex.Pattern USER_NOT_FOUND_CODE =
            java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"user_not_found\"");

    private final String baseUri;
    private final String token;
    private final RestClient restClient;

    public AuthAccountClient(@Value("${platform.org.auth-sync.uri:}") String configuredUri,
                             @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
                             @Value("${platform.org.internal-token:}") String token) {
        this.baseUri = resolveBase(configuredUri, jwkSetUri);
        this.token = token == null ? "" : token.trim();
        // auth-server가 멈춰도 워커 스레드가 묶이지 않게(다음 주기에 다시 보낸다)
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !baseUri.isEmpty() && !token.isEmpty();
    }

    String baseUri() {
        return baseUri;
    }

    /** 계정을 열거나(active=true) 닫는다(false — auth-server가 RT 전부 폐기·PAT 교환 거부). 멱등. */
    public Result setActive(long userId, boolean active) {
        if (!isConfigured()) return Result.NOT_CONFIGURED;
        try {
            restClient.put()
                    .uri(baseUri + "/internal/users/{id}/status", userId)
                    .header(HEADER, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("active", active))
                    .retrieve()
                    .toBodilessEntity();
            return Result.APPLIED;
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            // auth-server가 "사용자 없음"을 식별 코드로 밝힌 404만 완료로 친다. 코드 없는 404(엔드포인트 미배포·라우팅·
            // 프록시)를 완료로 읽으면 반영 기록이 맞춰져 영영 다시 보내지 않는다 — 그건 실패(재시도)다.
            if (status.value() == 404 && USER_NOT_FOUND_CODE.matcher(e.getResponseBodyAsString()).find()) {
                return Result.USER_NOT_FOUND;
            }
            log.warn("auth-server 계정 상태 반영 실패: user={} active={} status={}", userId, active, status.value());
            return Result.FAILED;
        } catch (RestClientException e) {
            log.warn("auth-server 계정 상태 반영 실패: user={} active={} {}", userId, active, e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    static String resolveBase(String configured, String jwkSetUri) {
        String explicit = configured == null ? "" : configured.trim();
        if (!explicit.isEmpty()) return stripSlash(explicit);
        if (jwkSetUri == null || jwkSetUri.isBlank()) return "";
        try {
            URI uri = URI.create(jwkSetUri.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String stripSlash(String v) {
        String s = v;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
