package com.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** P-07 — 서비스마다 갈라져 있던 내부 토큰 게이트의 단일 규칙. */
class InternalTokenFilterTest {

    private static MockHttpServletResponse run(InternalTokenFilter filter, String header, String value) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/x");
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
}
