package com.platform.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서비스 간 전용 경로(`/internal/**`)의 공유 토큰 게이트 — org·wiki·auth에 갈라져 있던 사본의 단일 구현(P-07).
 *
 * <ul>
 *   <li><b>trim</b>: 설정값과 헤더 값 모두 앞뒤 공백을 버리고 비교한다. `.env`의 트레일링 스페이스가 한쪽에만
 *       섞이면 모든 내부 호출이 이유 없이 403이 되는 사고(auth-server에서 실측)를 막는다.</li>
 *   <li><b>fail-closed</b>: 토큰이 비어 있으면(미설정) 헤더와 무관하게 403. env를 빠뜨린 배포가 내부 경로를 열지 않는다.</li>
 *   <li><b>상수시간 비교</b>: {@link MessageDigest#isEqual}.</li>
 *   <li>본문은 플랫폼 오류 계약 `{"error": 메시지}`.</li>
 *   <li><b>다중 값(N5, 0.18.0)</b>: 토큰 설정값은 콤마 구분 목록(`신,구`)이어도 된다 — 각 항목을 trim하고 빈 항목은
 *       버린 뒤 하나라도 같으면 통과. 수신 쪽에 두 값을 넣어 두고 발신을 하나씩 갈아 끼우면 무중단 회전이 된다.
 *       항목이 전부 비면 단일 값과 똑같이 fail-closed.</li>
 *   <li><b>경로별 집합(N5, 0.18.0)</b>: {@link #byPath}로 경로 패턴마다 다른 토큰 집합을 건다. 패턴은 등록 순서대로
 *       보고 <b>처음 맞는 것</b>의 집합만 쓴다. 어느 패턴에도 안 맞는 경로는 403 — 새 내부 경로를 만들고
 *       집합에 넣지 않으면 열리지 않는다.</li>
 * </ul>
 *
 * <p><b>{@code @Component}·{@code @Bean}으로 등록하지 말 것.</b> Boot는 {@code Filter} 빈을 서블릿 컨테이너
 * 레벨({@code /*})에도 자동 등록해 사용자 API 전체가 403이 된다(auth-server C1 사고). 서비스의
 * {@code SecurityConfig}가 {@code new}(또는 {@link #byPath})로 만들어 내부 체인에만 끼운다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 플랫폼 표준 헤더. auth-server의 게이트웨이·에이전트 경로만 역사적으로 {@code X-Internal-Secret}을 쓴다. */
    public static final String DEFAULT_HEADER = "X-Internal-Token";
    public static final String DEFAULT_MESSAGE = "내부 API 토큰이 유효하지 않습니다";

    private static final PathPatternParser PARSER = new PathPatternParser();

    /** 경로 패턴 하나와 그 경로에서 허용하는 토큰들. 단일 값 모드는 모든 경로에 맞는 패턴 하나({@code null})다. */
    private record Route(PathPattern pattern, List<byte[]> tokens) {
        boolean appliesTo(String path) {
            return pattern == null || pattern.matches(PathContainer.parsePath(path));
        }
    }

    private final String header;
    private final List<Route> routes;
    private final String body;

    public InternalTokenFilter(String token) {
        this(DEFAULT_HEADER, token, DEFAULT_MESSAGE);
    }

    public InternalTokenFilter(String header, String token) {
        this(header, token, DEFAULT_MESSAGE);
    }

    /** 모든 경로에 같은 토큰 집합. {@code token}은 단일 값 또는 콤마 구분 목록. */
    public InternalTokenFilter(String header, String token, String message) {
        this(header, List.of(new Route(null, parseTokens(token))), message);
    }

    private InternalTokenFilter(String header, List<Route> routes, String message) {
        if (header == null || header.isBlank()) throw new IllegalArgumentException("header must not be blank");
        this.header = header;
        this.routes = List.copyOf(routes);
        this.body = "{\"error\":\"" + (message == null ? DEFAULT_MESSAGE : message).replace("\"", "'") + "\"}";
    }

    /** {@link #byPath(String, Map, String)}의 기본 헤더({@value #DEFAULT_HEADER})·기본 문구 판. */
    public static InternalTokenFilter byPath(Map<String, String> tokensByPattern) {
        return byPath(DEFAULT_HEADER, tokensByPattern, DEFAULT_MESSAGE);
    }

    /**
     * 경로 패턴별 토큰 집합. 키는 Spring {@link PathPattern} 문법(`/internal/org/mail/**`, `/internal/org/members/*\/authz`),
     * 값은 단일 토큰 또는 콤마 구분 목록. <b>순서가 의미 있으므로 {@link java.util.LinkedHashMap}처럼 순서를 지키는 맵을
     * 넘긴다</b> — 처음 맞는 패턴의 집합만 본다. 경로는 컨텍스트 경로를 뺀 요청 경로로 맞춘다.
     */
    public static InternalTokenFilter byPath(String header, Map<String, String> tokensByPattern, String message) {
        if (tokensByPattern == null || tokensByPattern.isEmpty()) {
            throw new IllegalArgumentException("tokensByPattern must not be empty");
        }
        List<Route> routes = new ArrayList<>(tokensByPattern.size());
        tokensByPattern.forEach((pattern, tokens) -> routes.add(new Route(PARSER.parse(pattern), parseTokens(tokens))));
        return new InternalTokenFilter(header, routes, message);
    }

    /** 콤마 구분 목록 → trim한 비지 않은 항목들(UTF-8 바이트). null·빈 문자열·공백·콤마뿐이면 빈 목록. */
    static List<byte[]> parseTokens(String raw) {
        if (raw == null) return List.of();
        List<byte[]> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t.getBytes(StandardCharsets.UTF_8));
        }
        return List.copyOf(out);
    }

    /** 이 필터가 아무 요청도 통과시키지 않는 상태인가(모든 집합이 비었다) — 기동 로그용. */
    public boolean isClosed() {
        return routes.stream().allMatch(r -> r.tokens().isEmpty());
    }

    /**
     * 헤더 값이 설정 토큰(들) 중 하나와 같은가 — 단일 집합 모드용. 경로별 모드에서는 첫 집합이 아니라
     * 어느 경로에도 묶이지 않은 판정이 되므로 {@link #matches(String, String)}를 쓴다.
     */
    public boolean matches(String presented) {
        return routes.size() == 1 && routes.getFirst().pattern() == null
                && anyEqual(routes.getFirst().tokens(), presented);
    }

    /** 이 경로({@code /internal/...}, 컨텍스트 경로 제외)에서 헤더 값이 허용되는가. 맞는 패턴이 없으면 false. */
    public boolean matches(String path, String presented) {
        for (Route route : routes) {
            if (route.appliesTo(path)) return anyEqual(route.tokens(), presented);
        }
        return false;
    }

    private static boolean anyEqual(List<byte[]> tokens, String presented) {
        if (tokens.isEmpty() || presented == null) return false;
        byte[] candidate = presented.trim().getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        // 앞에서 맞아도 끝까지 비교한다 — 몇 번째 값과 맞았는지가 시간으로 새지 않게
        for (byte[] token : tokens) {
            ok |= MessageDigest.isEqual(token, candidate);
        }
        return ok;
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (uri == null) return "/";
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!matches(pathOf(request), request.getHeader(header))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(body);
            return;
        }
        chain.doFilter(request, response);
    }
}
