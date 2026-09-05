package com.platform.orgservice.security;

import com.platform.orgservice.member.MemberService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 인증된 요청의 JWT 클레임(sub/name/email/provider)으로 member를 JIT 미러링하고,
 * 그 자리에서 초대를 소진하거나 승인 대기로 격리한다.
 *
 * <p>여기서 하는 이유: 초대는 이메일이 키인데 그 이메일을 우리가 처음 보는 순간이 바로 첫 요청이다.
 * 로그인 이후 어느 화면을 먼저 열든 이 필터를 지나므로, 초대 소진과 격리를 한 곳에 둘 수 있다.
 *
 * <p>승인 대기(PENDING)·정지(SUSPENDED)·비활성(DEACTIVATED) 계정은 {@code /api/org/me}와 그 하위만
 * 지나간다 — 프론트가 "승인 대기" 화면을 그리려면 자기 상태는 읽을 수 있어야 하기 때문이다.
 * 나머지는 403으로 끊는다(wiki·alm 쪽은 gRPC 판정이 같은 기준으로 막는다).
 *
 * <p>SecurityConfig가 이 필터를 Security 체인(AuthorizationFilter 뒤)에 편입한다 —
 * SecurityContext가 채워진 뒤에 동작하며, MockMvc 테스트에서도 동일 체인이 재현된다.
 */
@Component
@RequiredArgsConstructor
public class MemberMirrorFilter extends OncePerRequestFilter {

    /** 상태와 무관하게 열어 두는 경로 — 자기 상태를 못 읽으면 안내 화면조차 그릴 수 없다. */
    private static final String ME_PATH = "/api/org/me";

    private final MemberService members;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            long id;
            try {
                id = Long.parseLong(jwtAuth.getToken().getSubject());
            } catch (NumberFormatException ignored) {
                // sub가 숫자가 아닌 토큰(외부 발급 등)은 미러링 생략
                chain.doFilter(request, response);
                return;
            }
            MemberService.MirrorResult result = members.mirror(id,
                    jwtAuth.getToken().getClaimAsString("name"),
                    jwtAuth.getToken().getClaimAsString("email"),
                    jwtAuth.getToken().getClaimAsString("provider"));
            if (result.blocked() && !isSelfPath(request)) {
                deny(response, result.reason());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isSelfPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return false;
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals(ME_PATH) || path.startsWith(ME_PATH + "/");
    }

    private static void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
