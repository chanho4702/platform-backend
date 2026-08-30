package com.platform.orgservice.config;

import com.platform.orgservice.security.MemberMirrorFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * JWT 디코더(JWKS + issuer/audience 검증)와 roles→ROLE_ 변환기는 common-starter가 준다(S-02).
 * 여기에는 이 서비스만의 것 — 어떤 경로를 열지, 어떤 필터를 끼울지 — 만 남긴다.
 */
@Configuration
public class SecurityConfig {

    /**
     * MemberMirrorFilter를 Security 체인에 명시 편입(AuthorizationFilter 뒤 = 인증 완료 후).
     * MockMvc(springSecurity())에서도 같은 체인이 재현되고, 서블릿 컨테이너 자동 등록으로
     * 이중 실행되더라도 OncePerRequestFilter가 요청당 1회를 보장한다.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                                    MemberMirrorFilter memberMirrorFilter) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                // 관리 도메인 — 공개 엔드포인트 없음. 세부 인가(GLOBAL ADMIN)는 PermissionFacade가 판정.
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .addFilterAfter(memberMirrorFilter, AuthorizationFilter.class);
        return http.build();
    }
}
