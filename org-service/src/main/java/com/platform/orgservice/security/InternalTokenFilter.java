package com.platform.orgservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /internal/org/**} 전용 게이트. 여기는 사용자 JWT가 오지 않는 서비스 간 경로다
 * (초대 확인·수락은 로그인 도중에 일어나 아직 우리 토큰이 없다).
 *
 * <p>토큰이 비어 있으면(미설정) 헤더 값과 무관하게 무조건 403이다 — env를 안 넣은 채 배포돼도
 * 내부 경로가 열리지 않는다(fail-closed). 비교는 상수시간으로 한다.
 *
 * <p><b>의도적으로 {@code @Component}가 아니다.</b> Boot는 {@code Filter} 빈을 서블릿 컨테이너
 * 레벨({@code /*})에도 자동 등록하므로, 빈으로 두면 이 필터가 모든 요청을 한 번 더 지나가며
 * 사용자 API 전체를 403으로 만든다(auth-server InternalSecretFilter에서 실측된 사고).
 * {@code SecurityConfig}가 {@code new}로 만들어 내부 체인에만 끼운다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Token";

    private final String token;

    public InternalTokenFilter(String token) {
        this.token = token == null ? "" : token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        boolean valid = !token.isEmpty()
                && header != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                                         header.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"내부 API 토큰이 유효하지 않습니다\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
