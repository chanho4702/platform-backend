package com.platform.orgservice.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Keycloak 관리 API 클라이언트 — 계정 비활성/활성만 한다.
 *
 * <p>우리가 사람을 퇴사 처리해도 Keycloak 계정이 살아 있으면 그 사람은 계속 로그인해 토큰을 받는다
 * (우리 쪽 판정이 막아 주지만, 계정 자체가 열려 있는 것은 다른 문제다). 그래서 DEACTIVATED는
 * Keycloak 계정을 함께 잠근다.
 *
 * <p><b>실패해도 예외를 던지지 않는다.</b> 우리 쪽 상태 변경이 Keycloak 가용성에 묶이면 관리자가
 * 퇴사 처리를 못 하게 된다. 실패는 {@code member_event}(KEYCLOAK_DISABLED_FAILED)에 남기고,
 * 그 사람의 다음 로그인은 어차피 PENDING/차단으로 걸린다.
 *
 * <p>시크릿이 없으면(로컬·테스트) 아무것도 하지 않는 no-op이다 — 미설정을 오류로 만들면
 * 관리 클라이언트 없이도 돌아야 하는 dev 클러스터가 뜨지 않는다.
 */
@Component
@Slf4j
public class KeycloakAdminClient {

    /** 결과를 상태로 돌려준다 — 호출측이 "안 했다"와 "하다 실패했다"를 구분해 기록한다. */
    public enum Result { APPLIED, DISABLED_BY_CONFIG, FAILED, USER_NOT_FOUND }

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String realmBase;   // {serverBase}/realms/{realm}
    private final String adminBase;   // {serverBase}/admin/realms/{realm}
    private final String clientId;
    private final String clientSecret;

    public KeycloakAdminClient(
            @Value("${platform.org.keycloak.issuer-uri:}") String issuerUri,
            @Value("${platform.org.keycloak.admin-client-id:}") String clientId,
            @Value("${platform.org.keycloak.admin-client-secret:}") String clientSecret) {
        this.realmBase = issuerUri == null ? "" : trimTrailingSlash(issuerUri);
        this.adminBase = toAdminBase(this.realmBase);
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        // Keycloak이 멈추면 서블릿 스레드가 함께 잠긴다 — best-effort는 예외는 삼켜도 행은 못 삼킨다.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public boolean isEnabled() {
        return !realmBase.isEmpty() && !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    public Result disableUserByEmail(String email) { return setEnabled(email, false); }

    public Result enableUserByEmail(String email) { return setEnabled(email, true); }

    private Result setEnabled(String email, boolean enabled) {
        if (!isEnabled() || email == null || email.isBlank()) return Result.DISABLED_BY_CONFIG;
        try {
            String token = accessToken();
            if (token == null) return Result.FAILED;
            String userId = findUserId(token, email);
            if (userId == null) return Result.USER_NOT_FOUND;
            HttpResponse<String> res = send(HttpRequest.newBuilder()
                    .uri(URI.create(adminBase + "/users/" + userId))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    // 없는 필드는 Keycloak이 건드리지 않는다 — enabled만 실어 보낸다.
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":" + enabled + "}",
                            StandardCharsets.UTF_8))
                    .build());
            if (res.statusCode() / 100 == 2) return Result.APPLIED;
            log.warn("Keycloak 계정 상태 변경 실패: status={}", res.statusCode());
            return Result.FAILED;
        } catch (Exception e) {
            // 메시지에 토큰이 섞이지 않도록 예외 종류와 짧은 메시지만 남긴다.
            log.warn("Keycloak 계정 상태 변경 실패: {}", e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    /** client_credentials — service account가 realm-management(manage-users)를 가진 전제. */
    private String accessToken() throws Exception {
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret);
        HttpResponse<String> res = send(HttpRequest.newBuilder()
                .uri(URI.create(realmBase + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build());
        if (res.statusCode() / 100 != 2) {
            log.warn("Keycloak 관리 토큰 발급 실패: status={}", res.statusCode());
            return null;
        }
        JsonNode node = json.readTree(res.body());
        JsonNode at = node.get("access_token");
        return at == null ? null : at.asText();
    }

    private String findUserId(String token, String email) throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder()
                .uri(URI.create(adminBase + "/users?exact=true&email=" + enc(email)))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
        if (res.statusCode() / 100 != 2) return null;
        JsonNode users = json.readTree(res.body());
        // 후보가 둘 이상이면 누구인지 모른다 — 남의 계정을 잠그느니 아무것도 하지 않는다.
        if (!users.isArray() || users.size() != 1) return null;
        JsonNode id = users.get(0).get("id");
        return id == null ? null : id.asText();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String value) {
        String v = value.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    /** {@code http://host/realms/sso-demo} → {@code http://host/admin/realms/sso-demo} */
    private static String toAdminBase(String realmBase) {
        int at = realmBase.lastIndexOf("/realms/");
        if (at < 0) return realmBase;
        return realmBase.substring(0, at) + "/admin" + realmBase.substring(at);
    }
}
