package com.platform.orgservice.team;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamKind;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 팀 리더 권한과 "전체 구성원" 팀.
 *
 * <p>리더가 자기 팀의 팀원을 다룰 수 있어야 하는 이유는 운영 속도다 — 팀원 한 명 넣는 데 전역 관리자를
 * 매번 불러야 하면 팀이 굳는다. 반대로 "전체 구성원"은 사람이 손대는 팀이 아니다: 활성 사용자면 자동으로
 * 들어가고 정지되면 빠진다. 수동 조작을 열어 두면 "공개인 줄 알았는데 안 보이는" 스페이스가 생긴다.
 */
@SpringBootTest
@ActiveProfiles("test")
class TeamLeadAndEveryoneTest {

    static final long ADMIN_ID = 100L;
    static final long LEAD_ID = 210L;
    static final long MEMBER_ID = 220L;
    static final long OUTSIDER_ID = 230L;

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired GrantEntryRepository grants;
    @Autowired EveryoneTeamService everyone;

    MockMvc mvc;
    long teamId;

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
        active(members, LEAD_ID, "Lead");
        active(members, MEMBER_ID, "Member");
        active(members, OUTSIDER_ID, "Outsider");
        everyone.ensure();

        teamId = teams.save(Team.of("플랫폼팀", null)).getId();
        teamMembers.save(TeamMember.of(teamId, LEAD_ID, TeamRole.LEAD));
    }

    @Test
    void 리더는_자기_팀에_팀원을_넣고_뺀다() throws Exception {
        mvc.perform(put("/api/org/teams/" + teamId + "/members/" + MEMBER_ID + "?role=MEMBER")
                        .with(asUser(LEAD_ID, "Lead")))
                .andExpect(status().isOk());
        assertThat(teamMembers.findByTeamIdAndMemberId(teamId, MEMBER_ID)).isPresent();

        mvc.perform(delete("/api/org/teams/" + teamId + "/members/" + MEMBER_ID)
                        .with(asUser(LEAD_ID, "Lead")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 리더가_아니면_팀원을_넣을_수_없다() throws Exception {
        mvc.perform(put("/api/org/teams/" + teamId + "/members/" + MEMBER_ID + "?role=MEMBER")
                        .with(asUser(OUTSIDER_ID, "Outsider")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 팀을 관리할 수 없습니다"));
    }

    @Test
    void 리더_지정은_PATCH로_한다() throws Exception {
        teamMembers.save(TeamMember.of(teamId, MEMBER_ID, TeamRole.MEMBER));

        mvc.perform(patch("/api/org/teams/" + teamId + "/members/" + MEMBER_ID)
                        .with(asUser(LEAD_ID, "Lead"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"LEAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("LEAD"));

        assertThat(teamMembers.findByTeamIdAndMemberId(teamId, MEMBER_ID).orElseThrow().getRole())
                .isEqualTo(TeamRole.LEAD);
    }

    @Test
    void 전체_구성원_팀은_수동으로_바꿀_수_없다() throws Exception {
        long everyoneId = teams.findFirstByKind(TeamKind.EVERYONE).orElseThrow().getId();

        mvc.perform(put("/api/org/teams/" + everyoneId + "/members/" + MEMBER_ID)
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("전체 구성원 팀은 자동으로 소속되므로 직접 추가할 수 없습니다"));

        mvc.perform(delete("/api/org/teams/" + everyoneId).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("전체 구성원 팀은 삭제할 수 없습니다"));

        mvc.perform(put("/api/org/teams/" + everyoneId).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"모두\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("전체 구성원 팀은 이름을 바꿀 수 없습니다"));
    }

    @Test
    void 목록은_구성원_수와_내_역할을_준다() throws Exception {
        mvc.perform(get("/api/org/teams?q=플랫폼").with(asUser(LEAD_ID, "Lead")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("STANDARD"))
                .andExpect(jsonPath("$[0].memberCount").value(1))
                .andExpect(jsonPath("$[0].myRole").value("LEAD"));

        mvc.perform(get("/api/org/teams?q=플랫폼").with(asUser(OUTSIDER_ID, "Outsider")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].myRole").doesNotExist());
    }

    @Test
    void 예약된_이름으로는_팀을_만들_수_없다() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/org/teams").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"전체 구성원\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("전체 구성원은 예약된 팀 이름입니다"));
    }
}
