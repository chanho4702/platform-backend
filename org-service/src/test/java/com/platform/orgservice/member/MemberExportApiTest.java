package com.platform.orgservice.member;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Member;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F-25 구성원 CSV 내보내기 — 관리자 전용, 목록과 같은 필터, 수식 주입 차단, 엑셀용 BOM. */
@SpringBootTest
@ActiveProfiles("test")
class MemberExportApiTest {

    static final long ADMIN_ID = 100L;
    static final long USER_ID = 200L;
    static final long SUSPENDED_ID = 300L;

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
        members.save(Member.of(USER_ID, "=cmd|' /C calc'!A0", "bob@test.com"));
        Member suspended = Member.of(SUSPENDED_ID, "Carol", "carol@test.com");
        suspended.suspend(java.time.Instant.now());
        members.save(suspended);
        everyone.ensure();
        everyone.add(ADMIN_ID);
        everyone.add(USER_ID);
        Team platform = teams.save(Team.of("플랫폼", null));
        Team design = teams.save(Team.of("디자인", null));
        teamMembers.save(TeamMember.of(platform.getId(), USER_ID, TeamRole.MEMBER));
        teamMembers.save(TeamMember.of(design.getId(), USER_ID, TeamRole.LEAD));
    }

    @Test
    void 기본_필터의_구성원을_이름_이메일_상태_팀으로_내려준다() throws Exception {
        MvcResult res = mvc.perform(get("/api/org/members/export").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"members.csv\""))
                .andReturn();

        byte[] body = res.getResponse().getContentAsByteArray();
        assertThat(Arrays.copyOf(body, 3)).containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        String[] lines = text.split("\r\n");
        assertThat(lines[0]).isEqualTo("\"이름\",\"이메일\",\"상태\",\"팀\"");
        // 기본 필터(ACTIVE·HUMAN) — 정지된 Carol은 빠진다. 정렬은 목록과 같은 이름순.
        assertThat(lines).hasSize(3);
        // 수식으로 시작하는 표시 이름은 글자로 묶이고, 전체 구성원 팀은 팀 열에 넣지 않는다.
        assertThat(lines[1]).isEqualTo("\"'=cmd|' /C calc'!A0\",\"bob@test.com\",\"활성\",\"디자인; 플랫폼\"");
        assertThat(lines[2]).isEqualTo("\"Admin\",\"admin@test.com\",\"활성\",\"\"");
    }

    @Test
    void 상태_필터를_목록과_같은_규칙으로_받는다() throws Exception {
        MvcResult res = mvc.perform(get("/api/org/members/export").param("status", "SUSPENDED")
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andReturn();
        String text = new String(res.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).contains("\"Carol\",\"carol@test.com\",\"일시 정지\",\"\"")
                .doesNotContain("bob@test.com");
    }

    @Test
    void 전역_관리자가_아니면_403과_오류_본문() throws Exception {
        mvc.perform(get("/api/org/members/export").with(asUser(USER_ID, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 알_수_없는_상태는_400과_오류_본문() throws Exception {
        mvc.perform(get("/api/org/members/export").param("status", "NOPE")
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
