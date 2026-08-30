package com.platform.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * 플랫폼 리소스 서버의 JWT 검증 규약 — 서비스마다 복제돼 있던 것(S-02).
 *
 * JWKS 서명에 더해 **issuer·audience**를 검증한다: 다른 발급자·다른 대상의 토큰은 서명이 맞아도 거부.
 * `issuer-uri` 디스커버리는 쓰지 않는다(컨테이너 split-horizon 확정 결정) — `jwk-set-uri`를 직접 준다.
 *
 * Boot의 리소스 서버 자동 구성보다 **앞에** 서야 한다. 뒤에 서면 Boot가 audience 검증이 없는 디코더를
 * 먼저 만들고 이 빈이 물러나 검증이 조용히 빠진다. 서비스가 같은 타입의 빈을 직접 두면 이 빈이 물러난다.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"})
@ConditionalOnClass({JwtDecoder.class, NimbusJwtDecoder.class})
@ConditionalOnProperty(prefix = "platform.jwt", name = {"issuer", "audience"})
public class PlatformResourceServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${platform.jwt.issuer}") String issuer,
            @Value("${platform.jwt.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audience)));
        return decoder;
    }

    /** auth-server가 넣는 `roles` 클레임 → `ROLE_*` 권한. */
    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
