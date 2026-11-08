package com.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P-07 — 서비스마다 갈라져 있던 내부 토큰 게이트의 단일 규칙. N5 — 다중 값(신,구)·경로별 토큰 집합. */
class InternalTokenFilterTest {

    private static MockHttpServletResponse run(InternalTokenFilter filter, String header, String value) throws Exception {
        return runAt(filter, "/internal/x", header, value);
    }

    private static MockHttpServletResponse runAt(InternalTokenFilter filter, String path, String header, String value)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        if (value != null) req.addHeader(header, value);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void 같은_토큰이면_통과한다() throws Exception {
        assertThat(run(new InternalTokenFilter("s3cret"), "X-Internal-Token", "s3cret").getStatus()).isEqualTo(200);
    }

    @Test
    void 설정값과_헤더의_앞뒤_공백은_무시한다() throws Exception {
        // .env 트레일링 스페이스 사고 — 한쪽에만 섞여도 내부 호출 전체가 막혔다(org·wiki 사본은 trim이 없었다)
        assertThat(run(new InternalTokenFilter("s3cret \n"), "X-Internal-Token", " s3cret").getStatus()).isEqualTo(200);
    }

    @Test
    void 토큰이_비어_있으면_무엇이_와도_403이다() throws Exception {
        MockHttpServletResponse res = run(new InternalTokenFilter("  "), "X-Internal-Token", "");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).isEqualTo("{\"error\":\"내부 API 토큰이 유효하지 않습니다\"}");
        assertThat(new InternalTokenFilter(null).isClosed()).isTrue();
    }

    @Test
    void 다르거나_헤더가_없으면_403이다() throws Exception {
        assertThat(run(new InternalTokenFilter("s3cret"), "X-Internal-Token", "other").getStatus()).isEqualTo(403);
        assertThat(run(new InternalTokenFilter("s3cret"), "X-Internal-Token", null).getStatus()).isEqualTo(403);
    }

    @Test
    void 헤더명과_문구를_바꿀_수_있다() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter("X-Internal-Secret", "s3cret", "내부 시크릿이 유효하지 않습니다");
        assertThat(run(filter, "X-Internal-Secret", "s3cret").getStatus()).isEqualTo(200);
        MockHttpServletResponse wrongHeader = run(filter, "X-Internal-Token", "s3cret");
        assertThat(wrongHeader.getStatus()).isEqualTo(403);
        assertThat(wrongHeader.getContentAsString()).contains("내부 시크릿이 유효하지 않습니다");
    }

    // ---- N5: 다중 값(무중단 회전 신,구) ----

    @Test
    void 콤마로_적은_신_구_두_값은_모두_통과하고_제3의_값은_403이다() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter("new-tok,old-tok");
        assertThat(run(filter, "X-Internal-Token", "new-tok").getStatus()).isEqualTo(200);
        assertThat(run(filter, "X-Internal-Token", "old-tok").getStatus()).isEqualTo(200);
        assertThat(run(filter, "X-Internal-Token", "other").getStatus()).isEqualTo(403);
        // 목록 문자열 자체를 헤더로 보내도 통과하지 않는다 — 값 하나하나와만 비교한다
        assertThat(run(filter, "X-Internal-Token", "new-tok,old-tok").getStatus()).isEqualTo(403);
    }

    @Test
    void 목록의_각_항목은_trim하고_빈_항목은_무시한다() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(" new-tok , ,\told-tok \n,");
        assertThat(run(filter, "X-Internal-Token", "new-tok").getStatus()).isEqualTo(200);
        assertThat(run(filter, "X-Internal-Token", " old-tok").getStatus()).isEqualTo(200);
        // 빈 항목이 "빈 헤더 허용"이 되면 안 된다
        assertThat(run(filter, "X-Internal-Token", "").getStatus()).isEqualTo(403);
        assertThat(run(filter, "X-Internal-Token", null).getStatus()).isEqualTo(403);
    }

    @Test
    void 항목이_전부_비면_fail_closed다() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(" , ,, ");
        assertThat(filter.isClosed()).isTrue();
        assertThat(run(filter, "X-Internal-Token", "").getStatus()).isEqualTo(403);
        assertThat(run(filter, "X-Internal-Token", ",").getStatus()).isEqualTo(403);
        assertThat(filter.matches("")).isFalse();
    }

    // ---- N5: 경로 패턴별 토큰 집합 ----

    private static InternalTokenFilter orgLike() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("/internal/org/members/agents/**", "agent-tok");
        routes.put("/internal/org/mail/**", "mail-tok,agent-tok");
        routes.put("/internal/org/invitations/**", "id-tok");
        routes.put("/internal/org/members/ensure", "id-tok");
        routes.put("/internal/org/members/*/authz", "id-tok");
        return InternalTokenFilter.byPath(routes);
    }

    @Test
    void 경로별로_허용_토큰_집합이_다르다() throws Exception {
        InternalTokenFilter f = orgLike();
        String h = "X-Internal-Token";
        assertThat(runAt(f, "/internal/org/mail", h, "mail-tok").getStatus()).isEqualTo(200);
        assertThat(runAt(f, "/internal/org/mail/status", h, "agent-tok").getStatus()).isEqualTo(200);
        assertThat(runAt(f, "/internal/org/mail", h, "id-tok").getStatus()).isEqualTo(403);

        assertThat(runAt(f, "/internal/org/members/agents", h, "agent-tok").getStatus()).isEqualTo(200);
        assertThat(runAt(f, "/internal/org/members/agents", h, "mail-tok").getStatus()).isEqualTo(403);
        assertThat(runAt(f, "/internal/org/members/agents", h, "id-tok").getStatus()).isEqualTo(403);

        assertThat(runAt(f, "/internal/org/invitations/by-token/abc", h, "id-tok").getStatus()).isEqualTo(200);
        assertThat(runAt(f, "/internal/org/members/ensure", h, "id-tok").getStatus()).isEqualTo(200);
        assertThat(runAt(f, "/internal/org/members/42/authz", h, "id-tok").getStatus()).isEqualTo(200);
        assertThat(runAt(f, "/internal/org/members/42/authz", h, "agent-tok").getStatus()).isEqualTo(403);
        assertThat(runAt(f, "/internal/org/invitations/accept", h, "mail-tok").getStatus()).isEqualTo(403);
    }

    @Test
    void 어느_패턴에도_안_맞는_경로는_어떤_토큰이든_403이다() throws Exception {
        InternalTokenFilter f = orgLike();
        MockHttpServletResponse res = runAt(f, "/internal/org/new-thing", "X-Internal-Token", "id-tok");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).isEqualTo("{\"error\":\"내부 API 토큰이 유효하지 않습니다\"}");
    }

    @Test
    void 처음_맞는_패턴이_이긴다() throws Exception {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("/internal/a/special", "special");
        routes.put("/internal/a/**", "general");
        InternalTokenFilter f = InternalTokenFilter.byPath(routes);
        assertThat(runAt(f, "/internal/a/special", "X-Internal-Token", "special").getStatus()).isEqualTo(200);
        assertThat(runAt(f, "/internal/a/special", "X-Internal-Token", "general").getStatus()).isEqualTo(403);
        assertThat(runAt(f, "/internal/a/other", "X-Internal-Token", "general").getStatus()).isEqualTo(200);
    }

    @Test
    void 한_경로의_집합만_비면_그_경로만_닫힌다() throws Exception {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("/internal/a/**", "  ");
        routes.put("/internal/b/**", "b-tok");
        InternalTokenFilter f = InternalTokenFilter.byPath(routes);
        assertThat(f.isClosed()).isFalse();
        assertThat(runAt(f, "/internal/a/x", "X-Internal-Token", "").getStatus()).isEqualTo(403);
        assertThat(runAt(f, "/internal/a/x", "X-Internal-Token", "b-tok").getStatus()).isEqualTo(403);
        assertThat(runAt(f, "/internal/b/x", "X-Internal-Token", "b-tok").getStatus()).isEqualTo(200);

        Map<String, String> allEmpty = new LinkedHashMap<>();
        allEmpty.put("/internal/a/**", "");
        allEmpty.put("/internal/b/**", null);
        assertThat(InternalTokenFilter.byPath(allEmpty).isClosed()).isTrue();
    }

    @Test
    void 컨텍스트_경로를_빼고_매칭하며_헤더명과_문구도_바꿀_수_있다() throws Exception {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("/internal/pat/**", "pat-tok");
        InternalTokenFilter f = InternalTokenFilter.byPath("X-Internal-Secret", routes, "내부 시크릿이 유효하지 않습니다");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/ctx/internal/pat/exchange");
        req.setContextPath("/ctx");
        req.addHeader("X-Internal-Secret", "pat-tok");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(200);

        MockHttpServletResponse denied = runAt(f, "/internal/pat/exchange", "X-Internal-Token", "pat-tok");
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getContentAsString()).contains("내부 시크릿이 유효하지 않습니다");
    }

    @Test
    void 경로별_매칭_규칙을_필터_밖에서도_쓸_수_있다() {
        InternalTokenFilter f = orgLike();
        assertThat(f.matches("/internal/org/mail", "agent-tok")).isTrue();
        assertThat(f.matches("/internal/org/mail", "id-tok")).isFalse();
        assertThat(f.matches("/internal/org/unknown", "id-tok")).isFalse();
    }
}
