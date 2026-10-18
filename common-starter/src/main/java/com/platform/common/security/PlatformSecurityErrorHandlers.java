package com.platform.common.security;

import com.platform.common.web.ErrorMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 보안 필터 체인이 끊는 401/403에도 플랫폼 본문 `{"error": 메시지}`를 싣는다(P-11). Spring Security 기본은 본문이 없다.
 *
 * <p>빈이 아니라 서비스가 자기 {@code SecurityFilterChain}에서 꽂는다 — 어느 체인에 쓸지는 서비스가 정한다:
 * <pre>
 * .oauth2ResourceServer(o -> o.jwt(...).authenticationEntryPoint(PlatformSecurityErrorHandlers.authenticationEntryPoint())
 *                                .accessDeniedHandler(PlatformSecurityErrorHandlers.accessDeniedHandler()))
 * </pre>
 * {@code WWW-Authenticate} 헤더(RFC 6750)는 기존 Bearer 처리기가 그대로 붙인다 — 본문만 더한다.
 */
public final class PlatformSecurityErrorHandlers {

    private PlatformSecurityErrorHandlers() {}

    public static AuthenticationEntryPoint authenticationEntryPoint() {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException e) -> {
            bearer.commence(request, response, e);
            writeBody(response, ErrorMessages.UNAUTHORIZED);
        };
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) -> {
            bearer.handle(request, response, e);
            writeBody(response, ErrorMessages.FORBIDDEN);
        };
    }

    private static void writeBody(HttpServletResponse response, String message) throws IOException {
        if (response.isCommitted()) return;
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
