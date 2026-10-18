package com.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** P-05(JWKS 타임아웃)·P-06(설정 누락 시 조용한 강등 대신 기동 실패). */
class PlatformResourceServerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformResourceServerAutoConfiguration.class));

    @Test
    void jwk_set_uri가_있고_issuer가_비면_기동이_실패한다() {
        runner.withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9000/.well-known/jwks.json",
                        "platform.jwt.audience=platform-api")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("platform.jwt.issuer");
                });
    }

    @Test
    void jwk_set_uri가_있고_audience가_공백이면_기동이_실패한다() {
        runner.withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9000/.well-known/jwks.json",
                        "platform.jwt.issuer=http://localhost:9000",
                        "platform.jwt.audience= ")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("platform.jwt.audience");
                });
    }

    @Test
    void 세_값이_다_있으면_디코더를_만든다() {
        runner.withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9000/.well-known/jwks.json",
                        "platform.jwt.issuer=http://localhost:9000",
                        "platform.jwt.audience=platform-api")
                .run(ctx -> assertThat(ctx).hasSingleBean(JwtDecoder.class));
    }

    @Test
    void jwk_set_uri가_없으면_리소스_서버가_아니므로_물러난다() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(JwtDecoder.class);
        });
    }

    @Test
    void 서비스가_자기_디코더를_두면_검증을_강제하지_않는다() {
        runner.withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9000/jwks")
                .withBean(JwtDecoder.class, () -> token -> { throw new UnsupportedOperationException(); })
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /**
     * 연결은 받되 응답하지 않는 JWKS 서버 — 타임아웃이 없으면 decode가 영원히 멈춘다.
     * 읽기 타임아웃(여기서는 300ms) 안쪽에서 예외로 끝나야 한다.
     */
    @Test
    void JWKS가_응답하지_않으면_읽기_타임아웃으로_끝난다() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<Socket> held = new ArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket s = server.accept();
                        held.add(s); // 요청을 읽지도 답하지도 않는다
                        InputStream ignored = s.getInputStream();
                    }
                } catch (IOException ignored) {
                    // 테스트 종료
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            runner.withPropertyValues(
                            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + server.getLocalPort() + "/jwks",
                            "platform.jwt.issuer=http://localhost:9000",
                            "platform.jwt.audience=platform-api",
                            "platform.jwt.jwks-read-timeout=300ms")
                    .run(ctx -> {
                        JwtDecoder decoder = ctx.getBean(JwtDecoder.class);
                        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                                assertThatThrownBy(() -> decoder.decode(rs256Shaped()))
                                        .isInstanceOf(Exception.class));
                    });
            for (Socket s : held) s.close();
        }
    }

    /** 서명 검증 전에 JWKS 조회가 일어나도록 kid가 있는 RS256 모양의 토큰(서명은 가짜). */
    private static String rs256Shaped() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString("{\"sub\":\"1\",\"iss\":\"http://localhost:9000\"}".getBytes(StandardCharsets.UTF_8));
        String sig = b64.encodeToString(new byte[256]);
        return header + "." + payload + "." + sig;
    }
}
