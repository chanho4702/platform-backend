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
 * 인증된 요청의 JWT 클레임(sub/name/email)으로 member를 JIT 미러링.
 * SecurityConfig가 이 필터를 Security 체인(AuthorizationFilter 뒤)에 편입한다 —
 * SecurityContext가 채워진 뒤에 동작하며, MockMvc 테스트에서도 동일 체인이 재현된다.
 */
@Component
@RequiredArgsConstructor
public class MemberMirrorFilter extends OncePerRequestFilter {

    private final MemberService members;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            try {
                long id = Long.parseLong(jwtAuth.getToken().getSubject());
                members.mirror(id,
                        jwtAuth.getToken().getClaimAsString("name"),
                        jwtAuth.getToken().getClaimAsString("email"));
            } catch (NumberFormatException ignored) {
                // sub가 숫자가 아닌 토큰(외부 발급 등)은 미러링 생략
            }
        }
        chain.doFilter(request, response);
    }
}
