package com.platform.orgservice.member;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static com.platform.orgservice.TestAuth.active;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-22 — 미러링 필터가 요청마다 쓰기 트랜잭션을 열지 않는다. 바뀐 것이 없으면 읽기 전용 경로가 판정을 끝내고,
 * 쓸 것이 있을 때(처음 보는 사람·이름/이메일 변경·승인 대기)만 빈 값을 돌려 쓰기 경로로 넘긴다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberMirrorFastPathTest {

    @Autowired MemberService service;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired TeamMemberRepository teamMembers;

    @BeforeEach
    void setup() {
        memberEvents.deleteAll();
        teamMembers.deleteAll();
        members.deleteAll();
    }

    @Test
    void 바뀐_것이_없는_활성_멤버는_읽기_전용으로_통과한다() {
        active(members, 1L, "Alice");
        Member saved = members.findById(1L).orElseThrow();

        var result = service.mirrorIfUnchanged(1L, saved.getDisplayName(), saved.getEmail());

        assertThat(result).isPresent();
        assertThat(result.get().blocked()).isFalse();
    }

    @Test
    void 클레임이_없으면_모름이라_덮지_않고_읽기_전용으로_끝난다() {
        active(members, 2L, "Bob");
        assertThat(service.mirrorIfUnchanged(2L, null, null)).isPresent();
    }

    @Test
    void 정지_계정도_읽기_전용으로_막는다() {
        Member m = members.save(Member.of(3L, "Carol", "carol@test.com"));
        m.suspend(java.time.Instant.now());
        members.save(m);

        var result = service.mirrorIfUnchanged(3L, "Carol", "carol@test.com");

        assertThat(result).isPresent();
        assertThat(result.get().blocked()).isTrue();
        assertThat(result.get().reason()).isEqualTo("정지된 계정입니다");
    }

    @Test
    void 이름이_바뀌었거나_처음_보거나_승인_대기면_쓰기_경로로_넘긴다() {
        active(members, 4L, "Dave");
        assertThat(service.mirrorIfUnchanged(4L, "Dave Kim", null)).isEmpty();
        assertThat(service.mirrorIfUnchanged(404L, "New", "new@test.com")).isEmpty();
        members.save(Member.joining(5L, "Eve", "eve@test.com"));
        assertThat(service.mirrorIfUnchanged(5L, "Eve", "eve@test.com")).isEmpty();
    }

    @Test
    void 에이전트는_JWT_클레임으로_덮지_않으므로_다른_이름이어도_읽기_전용이다() {
        members.save(Member.agentOf(6L, "지호", "agent@test.com"));
        assertThat(service.mirrorIfUnchanged(6L, "다른 이름", "x@test.com")).isPresent();
    }

    @Test
    void 빠른_경로는_읽기_전용_트랜잭션이다() {
        assertThat(MemberService.class.getMethods()).anySatisfy(m -> {
            assertThat(m.getName()).isEqualTo("mirrorIfUnchanged");
            assertThat(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
        });
    }
}
