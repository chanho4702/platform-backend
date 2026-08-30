package com.platform.searchservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** JWT 디코더·변환기는 common-starter가 준다(S-02). 여기는 이 서비스가 어떤 경로를 여는지만. */
@Configuration
public class SecurityConfig {

    /**
     * GraphQL은 **단일 URL**이라 플랫폼의 경로 기반 인가 규약이 그대로 먹지 않는다(설계 §8).
     * 그래서 `/graphql` 전체를 인증 필수로 잠그고, 무엇을 볼 수 있는지는 질의 단계에서
     * org-service grant로 필터한다 — 경로로는 구분할 수 없기 때문이다.
     *
     * 헬스체크만 열어둔다(compose healthcheck·배포 스모크가 토큰 없이 때린다).
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
