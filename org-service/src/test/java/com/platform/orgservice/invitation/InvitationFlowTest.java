package com.platform.orgservice.invitation;

import com.jayway.jsonpath.JsonPath;
import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.InvitationStatus;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamKind;
import com.platform.orgservice.domain.TeamRole;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asGoogleUser;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 초대 → 로그인 → 활성화의 한 바퀴(U1 §2).
 *
 * <p>여기서 지키려는 계약은 셋이다. 초대받은 사람은 <b>링크 없이 구글로 들어와도</b> 미리 정한 팀·권한을 가진
 * 활성 사용자가 된다. 초대 없이 들어온 사람은 <b>자기 상태 말고는 아무것도</b> 못 본다. 그리고 이메일을
 * 신뢰할 수 없는 로그인 경로에서는 초대가 있어도 이메일만으로는 소진되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class InvitationFlowTest {

    static final long ADMIN_ID = 100L;
    static final long INVITEE_ID = 501L;
    static final String INVITEE_EMAIL = "newbie@test.com";

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired InvitationRepository invitations;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired GrantEntryRepository grants;
    @Autowired EveryoneTeamService everyone;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberEvents.deleteAll();
        invitations.deleteAll();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        everyone.ensure();
    }

    @Test
    void 초대받은_사람은_구글로_들어오는_순간_팀과_권한을_가진_활성_사용자가_된다() throws Exception {
        long teamId = teams.save(Team.of("플랫폼팀", null)).getId();
        String body = """
                {"emails":["%s"],"teams":[{"teamId":%d,"role":"LEAD"}],
                 "grants":[{"scope":"SPACE","resourceId":"sp-1","role":"EDITOR"}],"message":"환영합니다"}
                """.formatted(INVITEE_EMAIL, teamId);

        String created = mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].email").value(INVITEE_EMAIL))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                // SMTP 미설정이므로 화면은 링크 복사로 안내한다
                .andExpect(jsonPath("$[0].mailSent").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.parse(created).read("$[0].inviteUrl", String.class)).contains("/invite/");

        // 초대 링크를 타지 않고 그냥 구글로 로그인 — 이메일 대조로 소진된다
        mvc.perform(get("/api/org/me").with(asGoogleUser(INVITEE_ID, "새사람", INVITEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.joinedVia").value("INVITE"));

        Member invitee = members.findById(INVITEE_ID).orElseThrow();
        assertThat(invitee.getStatus()).isEqualTo(MemberStatus.ACTIVE);

        assertThat(teamMembers.findByTeamIdAndMemberId(teamId, INVITEE_ID))
                .get().satisfies(tm -> assertThat(tm.getRole()).isEqualTo(TeamRole.LEAD));
        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, INVITEE_ID, ResourceKind.SPACE, "sp-1")).isPresent();

        long everyoneTeamId = teams.findFirstByKind(TeamKind.EVERYONE).orElseThrow().getId();
        assertThat(teamMembers.findByTeamIdAndMemberId(everyoneTeamId, INVITEE_ID)).isPresent();

        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
    }

    @Test
    void 초대_없이_들어온_계정은_자기_상태_말고는_403이다() throws Exception {
        mvc.perform(get("/api/org/me").with(asGoogleUser(INVITEE_ID, "낯선사람", "stranger@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(get("/api/org/teams").with(asGoogleUser(INVITEE_ID, "낯선사람", "stranger@test.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("승인 대기 중인 계정입니다"));

        // /api/org/me 하위(권한 목록·아바타)는 열려 있어야 승인 대기 화면을 그린다
        mvc.perform(get("/api/org/me/permissions").with(asGoogleUser(INVITEE_ID, "낯선사람", "stranger@test.com")))
                .andExpect(status().isOk());
    }

    @Test
    void 이메일을_신뢰할_수_없는_로그인은_초대가_있어도_이메일만으로는_소진되지_않는다() throws Exception {
        mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"" + INVITEE_EMAIL + "\"]}"))
                .andExpect(status().isCreated());

        // provider 클레임이 없는(=비밀번호 가입) 로그인. 이름 규칙상 이메일이 초대와 같아도 승인 대기다.
        mvc.perform(get("/api/org/me").with(asUser(INVITEE_ID, "Newbie")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    /** 승인은 초대 없이 들어온 사람을 위한 두 번째 문이다 — 승인하면 "전체 구성원"에 들어간다. */
    @Test
    void 관리자_승인으로도_활성화된다() throws Exception {
        mvc.perform(get("/api/org/me").with(asGoogleUser(INVITEE_ID, "낯선사람", "stranger@test.com")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/org/members/pending").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(INVITEE_ID));

        mvc.perform(post("/api/org/members/" + INVITEE_ID + "/approve").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.joinedVia").value("APPROVAL"));

        long everyoneTeamId = teams.findFirstByKind(TeamKind.EVERYONE).orElseThrow().getId();
        assertThat(teamMembers.findByTeamIdAndMemberId(everyoneTeamId, INVITEE_ID)).isPresent();

        mvc.perform(get("/api/org/teams").with(asGoogleUser(INVITEE_ID, "낯선사람", "stranger@test.com")))
                .andExpect(status().isOk());
    }

    /** 같은 이메일에 초대가 둘 살아 있으면 어느 프리셋이 적용됐는지 설명할 수 없다. */
    @Test
    void 재초대는_이전_초대를_만료시킨다() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"emails\":[\"" + INVITEE_EMAIL + "\"]}"))
                    .andExpect(status().isCreated());
        }
        assertThat(invitations.findAll().stream()
                .filter(i -> i.getStatus() == InvitationStatus.PENDING)).hasSize(1);
        assertThat(invitations.findAll().stream()
                .filter(i -> i.getStatus() == InvitationStatus.EXPIRED)).hasSize(1);
    }

    @Test
    void 이미_활성인_사람에게_보내는_초대는_409다() throws Exception {
        active(members, 777L, "이미있는사람");

        mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"이미있는사람@test.com\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("이미 활성 사용자입니다: 이미있는사람@test.com"));
    }

    @Test
    void 초대_권한이_없으면_403이다() throws Exception {
        active(members, 601L, "Nobody");

        mvc.perform(post("/api/org/invitations").with(asUser(601L, "Nobody"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"x@test.com\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("초대 권한이 없습니다"));
    }

    /** 리소스 ADMIN도 초대할 수 있지만, 프리셋은 자기가 관리하는 리소스로만 좁혀진다. */
    @Test
    void 스페이스_ADMIN은_자기_스페이스_프리셋으로만_초대한다() throws Exception {
        active(members, 602L, "SpaceAdmin");
        grants.save(GrantEntry.of(SubjectType.USER, 602L, ResourceKind.SPACE, "sp-1",
                com.platform.orgservice.domain.GrantRole.ADMIN));

        mvc.perform(post("/api/org/invitations").with(asUser(602L, "SpaceAdmin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"a@test.com\"],"
                                + "\"grants\":[{\"scope\":\"SPACE\",\"resourceId\":\"sp-1\",\"role\":\"VIEWER\"}]}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/org/invitations").with(asUser(602L, "SpaceAdmin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"b@test.com\"],"
                                + "\"grants\":[{\"scope\":\"SPACE\",\"resourceId\":\"sp-2\",\"role\":\"VIEWER\"}]}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/org/invitations").with(asUser(602L, "SpaceAdmin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"c@test.com\"],"
                                + "\"grants\":[{\"scope\":\"GLOBAL\",\"role\":\"ADMIN\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전역 역할은 전역 관리자만 부여할 수 있습니다"));
    }
}
