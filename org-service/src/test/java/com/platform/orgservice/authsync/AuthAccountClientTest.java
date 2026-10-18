package com.platform.orgservice.authsync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** P-02 — org → auth-server 계정 상태 호출의 실제 HTTP 모양(경로·헤더·본문)과 실패 분기. */
class AuthAccountClientTest {

    HttpServer server;
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> token = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(204);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            method.set(ex.getRequestMethod());
            path.set(ex.getRequestURI().getPath());
            token.set(ex.getRequestHeaders().getFirst("X-Internal-Token"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void 공유_토큰으로_PUT_internal_users_status를_부른다() {
        AuthAccountClient client = new AuthAccountClient(base() + "/", "", " tok ");

        assertThat(client.setActive(42L, false)).isEqualTo(AuthAccountClient.Result.APPLIED);

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/internal/users/42/status");
        assertThat(token.get()).isEqualTo("tok");
        assertThat(body.get()).isEqualTo("{\"active\":false}");
    }

    @Test
    void 사용자_없음과_실패를_구분한다() {
        AuthAccountClient client = new AuthAccountClient(base(), "", "tok");
        status.set(404);
        assertThat(client.setActive(1L, false)).isEqualTo(AuthAccountClient.Result.USER_NOT_FOUND);
        status.set(403);
        assertThat(client.setActive(1L, false)).isEqualTo(AuthAccountClient.Result.FAILED);
        status.set(503);
        assertThat(client.setActive(1L, false)).isEqualTo(AuthAccountClient.Result.FAILED);
    }

    @Test
    void 연결이_안_되면_예외가_아니라_실패_결과다() {
        AuthAccountClient client = new AuthAccountClient("http://127.0.0.1:1", "", "tok");
        assertThat(client.setActive(1L, true)).isEqualTo(AuthAccountClient.Result.FAILED);
    }

    @Test
    void 토큰이_없으면_부르지_않는다() {
        AuthAccountClient client = new AuthAccountClient(base(), "", "");
        assertThat(client.setActive(1L, false)).isEqualTo(AuthAccountClient.Result.NOT_CONFIGURED);
        assertThat(method.get()).isNull();
    }

    @Test
    void 주소를_비우면_JWKS_주소의_오리진을_쓴다() {
        assertThat(AuthAccountClient.resolveBase("", "http://auth-server:9000/.well-known/jwks.json"))
                .isEqualTo("http://auth-server:9000");
        assertThat(AuthAccountClient.resolveBase(" http://auth:9000/ ", "http://x/jwks")).isEqualTo("http://auth:9000");
        assertThat(AuthAccountClient.resolveBase("", "")).isEmpty();
    }
}
