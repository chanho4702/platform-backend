package com.platform.orgservice.config;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.JoinedVia;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberEventType;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.team.EveryoneTeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 최초 관리자 부트스트랩 — grant 시드 + 승인 대기 면제.
 *
 * <p>새 설치에서 가장 쉽게 막히는 지점이라 한 클래스에 모아 둔다: grant만 주고 PENDING으로 격리하면
 * 그 계정을 승인해 줄 활성 관리자가 없어(승인 API가 요구한다) 설치가 수동 SQL 없이는 진행되지 않는다.
 */
@SpringBootTest(properties = "platform.bootstrap-admin-id=42")
@ActiveProfiles("test")
class BootstrapAdminSeederTest {

    static final long BOOTSTRAP_ID = 42L;
    static final long OTHER_ID = 77L;

    @Autowired WebApplicationContext context;
    @Autowired GrantEntryRepository grants;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired EveryoneTeamService everyone;
    @Autowired BootstrapAdminSeeder seeder;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberEvents.deleteAll();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        seeder.run(null); // 각 테스트는 "막 설치하고 기동한" 상태에서 시작한다
    }

    @Test
    void 기동_시_지정_계정이_GLOBAL_ADMIN으로_시드된다() {
        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, BOOTSTRAP_ID, ResourceKind.GLOBAL, ""))
                .isPresent()
                .get().satisfies(g -> assertThat(g.getRole()).isEqualTo(GrantRole.ADMIN));
    }

    @Test
    void 기존_grant가_강등돼_있어도_재기동_시_ADMIN으로_복구된다() {
        var g = grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, BOOTSTRAP_ID, ResourceKind.GLOBAL, "").orElseThrow();
        g.changeRole(GrantRole.VIEWER);
        grants.saveAndFlush(g);

        seeder.run(null); // 재기동 시뮬레이션

        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, BOOTSTRAP_ID, ResourceKind.GLOBAL, "").orElseThrow().getRole())
                .isEqualTo(GrantRole.ADMIN);
    }

    @Test
    void 부트스트랩_관리자의_첫_로그인은_승인_대기를_건너뛴다() throws Exception {
        mvc.perform(get("/api/org/me").with(asUser(BOOTSTRAP_ID, "Owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.joinedVia").value("BOOTSTRAP"))
                .andExpect(jsonPath("$.globalRoles[0]").value("ADMIN"));

        // 격리가 실제로 풀렸는가 — 전역 관리자 전용 경로가 열려야 초대·승인으로 설치를 이어갈 수 있다
        mvc.perform(get("/api/org/members/pending").with(asUser(BOOTSTRAP_ID, "Owner")))
                .andExpect(status().isOk());

        Member member = members.findById(BOOTSTRAP_ID).orElseThrow();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getJoinedVia()).isEqualTo(JoinedVia.BOOTSTRAP);
        assertThat(memberEvents.findByMember(BOOTSTRAP_ID, Limit.of(10)))
                .singleElement()
                .satisfies(e -> assertThat(e.getType()).isEqualTo(MemberEventType.JOINED));
        assertThat(teamMembers.findByTeamIdAndMemberId(everyone.ensure().getId(), BOOTSTRAP_ID))
                .as("활성이 된 순간 전체 구성원 팀에 들어가야 공개 스페이스가 보인다")
                .isPresent();
    }

    @Test
    void 재로그인과_재기동을_반복해도_승격은_한_번만_기록된다() throws Exception {
        mvc.perform(get("/api/org/me").with(asUser(BOOTSTRAP_ID, "Owner"))).andExpect(status().isOk());
        seeder.run(null);
        mvc.perform(get("/api/org/me").with(asUser(BOOTSTRAP_ID, "Owner"))).andExpect(status().isOk());
        seeder.run(null);

        assertThat(members.findById(BOOTSTRAP_ID).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(memberEvents.findByMember(BOOTSTRAP_ID, Limit.of(10))).hasSize(1);
        assertThat(teamMembers.findAll()).hasSize(1); // 전체 구성원 합류도 멱등
    }

    @Test
    void 기동_시_승인_대기로_갇힌_부트스트랩_행을_활성으로_올린다() {
        // 이 수정 전에 로그인했거나, 환경변수를 나중에 붙인 설치 — 수동 SQL 없이 풀려야 한다
        members.saveAndFlush(Member.joining(BOOTSTRAP_ID, "Owner", "owner@test.com"));

        seeder.run(null);

        Member member = members.findById(BOOTSTRAP_ID).orElseThrow();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getJoinedVia()).isEqualTo(JoinedVia.BOOTSTRAP);
        assertThat(memberEvents.findByMember(BOOTSTRAP_ID, Limit.of(10))).hasSize(1);
        assertThat(teamMembers.findByTeamIdAndMemberId(everyone.ensure().getId(), BOOTSTRAP_ID)).isPresent();
    }

    @Test
    void 부트스트랩_id가_아닌_첫_로그인은_여전히_승인_대기다() throws Exception {
        mvc.perform(get("/api/org/me").with(asUser(OTHER_ID, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/org/members/pending").with(asUser(OTHER_ID, "Bob")))
                .andExpect(status().isForbidden());

        Member member = members.findById(OTHER_ID).orElseThrow();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.PENDING);
        assertThat(member.getJoinedVia()).isEqualTo(JoinedVia.LEGACY);
        assertThat(memberEvents.findByMember(OTHER_ID, Limit.of(10))).isEmpty();
    }

    @Test
    void 정지된_부트스트랩_계정은_재기동이_되살리지_않는다() {
        Member member = members.saveAndFlush(Member.of(BOOTSTRAP_ID, "Owner", "owner@test.com"));
        member.suspend(Instant.now());
        members.saveAndFlush(member);

        seeder.run(null);

        assertThat(members.findById(BOOTSTRAP_ID).orElseThrow().getStatus())
                .as("사람이 일부러 끊은 계정을 기동이 되살리면 안 된다")
                .isEqualTo(MemberStatus.SUSPENDED);
        assertThat(memberEvents.findByMember(BOOTSTRAP_ID, Limit.of(10))).isEmpty();
    }
}
