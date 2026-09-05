package com.platform.orgservice.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak 관리 클라이언트 — 가짜 Admin REST 서버로 검증한다.
 *
 * <p>검증하려는 것은 "성공했다"보다 <b>실패했을 때 조용히 돌아온다</b>는 쪽이다. 이 호출이 예외를 던지면
 * 관리자가 퇴사 처리를 못 하게 되고, 그러면 우리 쪽 상태 변경이 Keycloak 가용성에 묶인다.
 */
class KeycloakAdminClientTest {

    HttpServer server;
    List<String> paths;
    volatile int usersStatus = 200;
    volatile String usersBody = "[{\"id\":\"kc-user-1\"}]";
    volatile int updateStatus = 204;

    @BeforeEach
    void startServer() throws IOException {
        paths = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/test/protocol/openid-connect/token", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            drain(exchange);
            respond(exchange, 200, "{\"access_token\":\"fake-admin-token\"}");
        });
        server.createContext("/admin/realms/test/users", exchange -> {
            paths.add(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
            drain(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, usersStatus, usersBody);
            } else {
                respond(exchange, updateStatus, "");
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private KeycloakAdminClient client() {
        return new KeycloakAdminClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test",
                "platform-admin", "secret");
    }

    @Test
    void 계정을_찾아_비활성화한다() {
        assertThat(client().disableUserByEmail("someone@test.com"))
                .isEqualTo(KeycloakAdminClient.Result.APPLIED);
        assertThat(paths).anyMatch(p -> p.contains("exact=true") && p.contains("email=someone"));
        assertThat(paths).anyMatch(p -> p.contains("/users/kc-user-1"));
    }

    @Test
    void 계정이_없으면_아무것도_하지_않는다() {
        usersBody = "[]";
        assertThat(client().disableUserByEmail("nobody@test.com"))
                .isEqualTo(KeycloakAdminClient.Result.USER_NOT_FOUND);
    }

    /** 같은 이메일 후보가 둘이면 누구인지 모른다 — 남의 계정을 잠그느니 아무것도 하지 않는다. */
    @Test
    void 후보가_둘이면_잠그지_않는다() {
        usersBody = "[{\"id\":\"a\"},{\"id\":\"b\"}]";
        assertThat(client().disableUserByEmail("dup@test.com"))
                .isEqualTo(KeycloakAdminClient.Result.USER_NOT_FOUND);
    }

    @Test
    void Keycloak이_실패해도_예외를_던지지_않는다() {
        updateStatus = 500;
        assertThat(client().enableUserByEmail("someone@test.com"))
                .isEqualTo(KeycloakAdminClient.Result.FAILED);
    }

    /** 시크릿이 없으면 no-op — 관리 클라이언트 없이도 dev 클러스터가 떠야 한다. */
    @Test
    void 시크릿이_없으면_설정_비활성으로_돌아온다() {
        KeycloakAdminClient unconfigured = new KeycloakAdminClient(
                "http://127.0.0.1:1/realms/test", "platform-admin", "");
        assertThat(unconfigured.isEnabled()).isFalse();
        assertThat(unconfigured.disableUserByEmail("x@test.com"))
                .isEqualTo(KeycloakAdminClient.Result.DISABLED_BY_CONFIG);
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
