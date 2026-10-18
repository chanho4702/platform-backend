package com.platform.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 서비스 간 전용 경로(`/internal/**`)의 공유 토큰 게이트 — org·wiki·auth에 갈라져 있던 사본의 단일 구현(P-07).
 *
 * <ul>
 *   <li><b>trim</b>: 설정값과 헤더 값 모두 앞뒤 공백을 버리고 비교한다. `.env`의 트레일링 스페이스가 한쪽에만
 *       섞이면 모든 내부 호출이 이유 없이 403이 되는 사고(auth-server에서 실측)를 막는다.</li>
 *   <li><b>fail-closed</b>: 토큰이 비어 있으면(미설정) 헤더와 무관하게 403. env를 빠뜨린 배포가 내부 경로를 열지 않는다.</li>
 *   <li><b>상수시간 비교</b>: {@link MessageDigest#isEqual}.</li>
 *   <li>본문은 플랫폼 오류 계약 `{"error": 메시지}`.</li>
 * </ul>
 *
 * <p><b>{@code @Component}·{@code @Bean}으로 등록하지 말 것.</b> Boot는 {@code Filter} 빈을 서블릿 컨테이너
 * 레벨({@code /*})에도 자동 등록해 사용자 API 전체가 403이 된다(auth-server C1 사고). 서비스의
 * {@code SecurityConfig}가 {@code new}로 만들어 내부 체인에만 끼운다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 플랫폼 표준 헤더. auth-server의 게이트웨이·에이전트 경로만 역사적으로 {@code X-Internal-Secret}을 쓴다. */
    public static final String DEFAULT_HEADER = "X-Internal-Token";
    public static final String DEFAULT_MESSAGE = "내부 API 토큰이 유효하지 않습니다";

    private final String header;
    private final byte[] token;
    private final String body;

    public InternalTokenFilter(String token) {
        this(DEFAULT_HEADER, token, DEFAULT_MESSAGE);
    }

    public InternalTokenFilter(String header, String token) {
        this(header, token, DEFAULT_MESSAGE);
    }

    public InternalTokenFilter(String header, String token, String message) {
        if (header == null || header.isBlank()) throw new IllegalArgumentException("header must not be blank");
        this.header = header;
        String trimmed = token == null ? "" : token.trim();
        this.token = trimmed.getBytes(StandardCharsets.UTF_8);
        this.body = "{\"error\":\"" + (message == null ? DEFAULT_MESSAGE : message).replace("\"", "'") + "\"}";
    }

    /** 이 필터가 아무 요청도 통과시키지 않는 상태인가(토큰 미설정) — 기동 로그용. */
    public boolean isClosed() {
        return token.length == 0;
    }

    /** 헤더 값이 설정 토큰과 같은가. 필터 밖(예: 컨트롤러 단위 검사)에서도 같은 규칙을 쓰도록 공개한다. */
    public boolean matches(String presented) {
        if (token.length == 0 || presented == null) return false;
        return MessageDigest.isEqual(token, presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!matches(request.getHeader(header))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(body);
            return;
        }
        chain.doFilter(request, response);
    }
}
