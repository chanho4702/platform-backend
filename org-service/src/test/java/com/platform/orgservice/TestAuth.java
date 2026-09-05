package com.platform.orgservice;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.repository.MemberRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public class TestAuth {

    /** sub=userId. jwt() post-processor는 컨버터를 거치지 않으므로 authorities 직접 지정. */
    public static RequestPostProcessor asUser(long id, String name) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("name", name)
                        .claim("email", name.toLowerCase() + "@test.com").claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    public static RequestPostProcessor asAdmin(long id, String name) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("name", name)
                        .claim("email", name.toLowerCase() + "@test.com").claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** 구글처럼 이메일을 신뢰할 수 있는 로그인 — 이 경로에서만 토큰 없이 초대가 소진된다(U1). */
    public static RequestPostProcessor asGoogleUser(long id, String name, String email) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("name", name)
                        .claim("email", email).claim("provider", "GOOGLE").claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /**
     * 활성 멤버를 미리 심는다.
     *
     * <p>U1부터 JIT 미러링은 처음 보는 사람을 <b>PENDING</b>으로 만들고 {@code /api/org/me} 밖으로는
     * 403으로 막는다(초대 없는 가입 차단). 그 격리를 검증하는 테스트가 아니라면 활성 사용자로 시작해야
     * 하므로, 화면 테스트는 여기서 멤버를 심고 시작한다.
     */
    public static Member active(MemberRepository members, long id, String name) {
        return members.save(Member.of(id, name, name.toLowerCase() + "@test.com"));
    }
}
