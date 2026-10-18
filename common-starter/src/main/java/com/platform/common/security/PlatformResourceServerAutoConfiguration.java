package com.platform.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 플랫폼 리소스 서버의 JWT 검증 규약 — 서비스마다 복제돼 있던 것(S-02).
 *
 * JWKS 서명에 더해 **issuer·audience**를 검증한다: 다른 발급자·다른 대상의 토큰은 서명이 맞아도 거부.
 * `issuer-uri` 디스커버리는 쓰지 않는다(컨테이너 split-horizon 확정 결정) — `jwk-set-uri`를 직접 준다.
 *
 * Boot의 리소스 서버 자동 구성보다 **앞에** 서야 한다. 뒤에 서면 Boot가 audience 검증이 없는 디코더를
 * 먼저 만들고 이 빈이 물러나 검증이 조용히 빠진다. 서비스가 같은 타입의 빈을 직접 두면 이 빈이 물러난다.
 *
 * <p><b>켜지는 조건은 {@code jwk-set-uri}다(0.17.0, P-06).</b> 예전에는 {@code platform.jwt.issuer/audience}가
 * 있어야 켜져서, 새 서비스가 그 키를 빠뜨리면 구성이 통째로 빠지고 Boot 기본 디코더(audience 미검증·roles 변환 없음)가
 * 조용히 떴다. 이제 JWKS를 쓰는 리소스 서버인데 issuer/audience가 비어 있으면 **기동이 실패한다**. 의도적으로 이
 * 검증을 빼는 인스턴스(위키 공개 문서 docs 프로필)는 지금처럼 이 자동 구성을 {@code exclude}한다.
 *
 * <p>JWKS 조회에는 연결 2초·읽기 3초 타임아웃을 건다(P-05). 기본 {@code RestTemplate}은 무한 대기라, 키 캐시가
 * 만료되는 순간 auth-server가 느리면 모든 리소스 서버의 인증 경로가 함께 멈췄다.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"})
@ConditionalOnClass({JwtDecoder.class, NimbusJwtDecoder.class})
@ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "jwk-set-uri")
public class PlatformResourceServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${platform.jwt.issuer:}") String issuer,
            @Value("${platform.jwt.audience:}") String audience,
            @Value("${platform.jwt.jwks-connect-timeout:2s}") String connectTimeout,
            @Value("${platform.jwt.jwks-read-timeout:3s}") String readTimeout) {
        requireSet("platform.jwt.issuer", issuer);
        requireSet("platform.jwt.audience", audience);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .restOperations(jwksRestTemplate(
                        DurationStyle.detectAndParse(connectTimeout.trim()),
                        DurationStyle.detectAndParse(readTimeout.trim())))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(issuer.trim()),
                new AudienceValidator(audience.trim())));
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

    static RestTemplate jwksRestTemplate(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    private static void requireSet(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 가 비어 있습니다 — JWKS 리소스 서버는 issuer·audience 검증이 필수다. "
                    + "값을 설정하거나, 의도적으로 플랫폼 JWT 검증을 빼는 인스턴스라면 "
                    + PlatformResourceServerAutoConfiguration.class.getName() + " 를 spring.autoconfigure.exclude에 넣는다.");
        }
    }
}
