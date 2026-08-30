package com.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** board-service에 있던 검증(S-02 이관) — 다른 대상의 토큰은 서명이 맞아도 거부. */
class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("platform-api");

    private Jwt jwt(List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("1");
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }

    @Test
    void 기대한_audience면_통과한다() {
        assertThat(validator.validate(jwt(List.of("platform-api"))).hasErrors()).isFalse();
    }

    @Test
    void 다른_audience는_거부한다() {
        assertThat(validator.validate(jwt(List.of("other-api"))).hasErrors()).isTrue();
    }

    @Test
    void audience가_없어도_거부한다() {
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
    }
}
