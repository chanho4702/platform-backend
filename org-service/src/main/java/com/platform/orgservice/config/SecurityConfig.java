package com.platform.orgservice.config;

import com.platform.orgservice.security.InternalTokenFilter;
import com.platform.orgservice.security.MemberMirrorFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * JWT 디코더(JWKS + issuer/audience 검증)와 roles→ROLE_ 변환기는 common-starter가 준다(S-02).
 * 여기에는 이 서비스만의 것 — 어떤 경로를 열지, 어떤 필터를 끼울지 — 만 남긴다.
 *
 * {@code @EnableMethodSecurity}는 AgentMemberController의 {@code @PreAuthorize("hasRole('ADMIN')")}가
 * 동작하려면 필요하다 — 이 모듈의 다른 컨트롤러는 PermissionFacade로 자체 인가하므로 지금까지는 없었다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 서비스 간 전용 경로. 사용자 JWT가 아니라 {@link InternalTokenFilter}가 인증을 전담한다 —
     * 초대 확인·수락은 로그인 <b>도중</b>에 일어나 아직 우리 토큰이 없기 때문이다.
     * 게이트웨이는 이 경로를 라우팅하지 않는다(클러스터 내부 전용).
     *
     * <p>필터를 빈으로 등록하지 않고 {@code new}로 만드는 이유: Boot는 {@code Filter} 빈을
     * 서블릿 컨테이너 레벨({@code /*})에도 자동 등록해, 사용자 API 전체가 403이 되는 사고가
     * auth-server에서 실측됐다. 이 체인에만 있는 지역 인스턴스로 둔다.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain internalChain(HttpSecurity http,
                                      @Value("${platform.org.internal-token:}") String internalToken) throws Exception {
        http
                .securityMatcher("/internal/org/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new InternalTokenFilter(internalToken), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

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
                // OpenAPI 스펙(JSON)만 공개. 게이트웨이·nginx가 /v3를 라우팅하지 않아 클러스터 내부 전용이고,
                // 문서 생성기(myFront)가 토큰 없이 받아 간다. UI는 없다.
                // 그 밖은 관리 도메인 — 공개 엔드포인트 없음. 세부 인가(GLOBAL ADMIN)는 PermissionFacade가 판정.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                        // 헬스·빌드 정보. 내부 전용 체인(/internal/org/**)에는 걸리지 않으므로 여기서 연다.
                        // 게이트웨이가 상태판을 그리려고 토큰 없이 부르고, 나머지 /actuator/**는 애초에 노출하지 않는다.
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .addFilterAfter(memberMirrorFilter, AuthorizationFilter.class);
        return http.build();
    }
}
