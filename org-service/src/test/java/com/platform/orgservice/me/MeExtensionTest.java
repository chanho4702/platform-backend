package com.platform.orgservice.me;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.domain.TeamRole;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/org/me} 확장(U1).
 *
 * <p>프론트의 전역 관리자 판정을 이 하나로 모으기 위한 필드다 — 지금까지는 관리자 전용 엔드포인트를
 * 찔러 보고 403이 아니면 관리자로 쳤는데, 그러면 판정 기준이 화면마다 갈라진다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MeExtensionTest {

    static final long ADMIN_ID = 100L;
    static final long USER_ID = 200L;

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired GrantEntryRepository grants;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired EveryoneTeamService everyone;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberEvents.deleteAll();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        active(members, USER_ID, "Bob");
        everyone.ensure();
    }

    @Test
    void 전역_역할과_팀과_상태를_함께_준다() throws Exception {
        long teamId = teams.save(Team.of("플랫폼팀", null)).getId();
        teamMembers.save(TeamMember.of(teamId, ADMIN_ID, TeamRole.LEAD));

        mvc.perform(get("/api/org/me").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ADMIN_ID))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.kind").value("HUMAN"))
                .andExpect(jsonPath("$.joinedVia").value("LEGACY"))
                .andExpect(jsonPath("$.globalRoles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.teams[0].name").value("플랫폼팀"))
                .andExpect(jsonPath("$.teams[0].role").value("LEAD"));
    }

    @Test
    void 관리자가_아니면_globalRoles가_비어_있다() throws Exception {
        mvc.perform(get("/api/org/me").with(asUser(USER_ID, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalRoles").isEmpty());
    }
}
