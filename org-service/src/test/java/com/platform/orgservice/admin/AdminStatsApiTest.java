package com.platform.orgservice.admin;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Invitation;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.InvitationRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.team.EveryoneTeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 대시보드가 읽는 조직 현황.
 *
 * <p>지키려는 것은 셋이다 — 전역 관리자만 읽을 수 있다는 것, 사람과 에이전트를 이중으로 세지 않는다는 것,
 * 그리고 상태 네 개가 값이 0이어도 응답에 남아 화면이 키 존재를 확인하지 않아도 된다는 것.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminStatsApiTest {

    static final long ADMIN_ID = 100L;
    static final long ACTIVE_ID = 200L;
    static final long PENDING_ID = 201L;
    static final long SUSPENDED_ID = 202L;
    static final long DEACTIVATED_ID = 203L;
    static final long AGENT_ID = 300L;

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired GrantEntryRepository grants;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired InvitationRepository invitations;
    @Autowired EveryoneTeamService everyone;
    @Autowired OrgStatsService stats;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        stats.evictAll(); // 60초 캐시가 테스트 사이를 넘어오지 않게
        invitations.deleteAll();
        memberEvents.deleteAll();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();

        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        active(members, ACTIVE_ID, "Bob");
        members.save(Member.joining(PENDING_ID, "Pending", "pending@test.com"));
        Member suspended = members.save(Member.of(SUSPENDED_ID, "Suspended", "suspended@test.com"));
        suspended.suspend(Instant.now());
        members.save(suspended);
        Member gone = members.save(Member.of(DEACTIVATED_ID, "Gone", "gone@test.com"));
        gone.deactivate(Instant.now());
        members.save(gone);
        members.save(Member.agentOf(AGENT_ID, "Agent", "agent@test.com"));

        everyone.ensure();                              // 팀 1
        teams.save(Team.of("플랫폼", "플랫폼 팀"));       // 팀 2

        Instant expiresAt = Instant.now().plus(Duration.ofDays(7));
        invitations.save(Invitation.of("a@test.com", "hash-a", ADMIN_ID, null, expiresAt));
        invitations.save(Invitation.of("b@test.com", "hash-b", ADMIN_ID, null, expiresAt));
    }

    @Test
    void 전역_관리자는_상태별_멤버_수와_팀_초대_수를_읽는다() throws Exception {
        mvc.perform(get("/api/org/admin/stats").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.ACTIVE").value(2))       // Admin + Bob
                .andExpect(jsonPath("$.members.PENDING").value(1))
                .andExpect(jsonPath("$.members.SUSPENDED").value(1))
                .andExpect(jsonPath("$.members.DEACTIVATED").value(1))
                .andExpect(jsonPath("$.agents").value(1))               // 에이전트는 members에 섞이지 않는다
                .andExpect(jsonPath("$.teams").value(2))
                .andExpect(jsonPath("$.pendingInvitations").value(2));
    }

    @Test
    void 비관리자는_403이다() throws Exception {
        mvc.perform(get("/api/org/admin/stats").with(asUser(ACTIVE_ID, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    /** 상태가 하나도 없어도 키 넷은 0으로 남는다 — 화면이 undefined를 다루지 않게. */
    @Test
    void 없는_상태도_0으로_채워진다() throws Exception {
        members.deleteAll();
        active(members, ADMIN_ID, "Admin");
        stats.evictAll();

        mvc.perform(get("/api/org/admin/stats").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.ACTIVE").value(1))
                .andExpect(jsonPath("$.members.PENDING").value(0))
                .andExpect(jsonPath("$.members.SUSPENDED").value(0))
                .andExpect(jsonPath("$.members.DEACTIVATED").value(0))
                .andExpect(jsonPath("$.agents").value(0));
    }

    /** 같은 60초 안에서는 두 번째 호출이 DB를 다시 세지 않는다. */
    @Test
    void 통계는_60초_캐시된다() throws Exception {
        mvc.perform(get("/api/org/admin/stats").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(jsonPath("$.teams").value(2));

        teams.save(Team.of("추가 팀", null));

        mvc.perform(get("/api/org/admin/stats").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(jsonPath("$.teams").value(2));   // 캐시된 값 그대로

        stats.evictAll();
        mvc.perform(get("/api/org/admin/stats").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(jsonPath("$.teams").value(3));
    }
}
