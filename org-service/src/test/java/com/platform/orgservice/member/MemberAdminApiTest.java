package com.platform.orgservice.member;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.domain.TeamKind;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자 목록·상태 전이.
 *
 * <p>상태 전이에서 지키려는 것은 하나다 — <b>아무도 권한을 되돌릴 수 없는 상태를 만들 수 없다</b>.
 * 마지막 전역 관리자의 정지·비활성, 그리고 자기 자신의 비활성이 그것이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberAdminApiTest {

    static final long ADMIN_ID = 100L;
    static final long OTHER_ADMIN_ID = 101L;
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
        everyone.add(USER_ID);
    }

    /**
     * 목록은 배열이다 — ALM·위키의 사람 선택 화면이 이 모양을 직접 받는다.
     * 기본 필터가 ACTIVE·HUMAN이라 에이전트 페르소나는 사람 목록에 섞이지 않는다.
     */
    @Test
    void 목록은_배열이고_기본_필터는_ACTIVE_HUMAN이다() throws Exception {
        members.save(Member.agentOf(9001L, "에이전트", "agent@test.com"));

        mvc.perform(get("/api/org/members").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.kind=='AGENT')]").isEmpty());
    }

    /** 전부가 필요하면 명시한다 — 기본값을 바꾼 대신 되돌릴 손잡이를 남긴다. */
    @Test
    void status_ALL_kind_ALL이면_전부_준다() throws Exception {
        members.save(Member.agentOf(9001L, "에이전트", "agent@test.com"));
        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/org/members?status=ALL&kind=ALL").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void 알_수_없는_필터_값은_400이다() throws Exception {
        mvc.perform(get("/api/org/members?status=몰라").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("알 수 없는 사용자 상태입니다: 몰라"));
    }

    /** 페이지네이션은 별도 경로다 — 한 경로가 두 모양을 내면 어느 쪽이 계약인지 흐려진다. */
    @Test
    void 페이지_경로는_봉투를_준다() throws Exception {
        mvc.perform(get("/api/org/members/page?page=0&size=10").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void 검색은_이름과_이메일_부분일치다() throws Exception {
        mvc.perform(get("/api/org/members/page?q=bo").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(USER_ID));

        mvc.perform(get("/api/org/members?q=bo").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(USER_ID));
    }

    /** 이름의 원천은 Keycloak 클레임이다 — 여기서 고쳐 봐야 다음 요청의 미러링이 되돌린다. */
    @Test
    void 표시_이름은_PATCH로_바꿀_수_없다() throws Exception {
        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"displayName\":\"다른이름\"}"))
                .andExpect(status().isOk());

        assertThat(members.findById(USER_ID).orElseThrow().getDisplayName()).isEqualTo("Bob");
    }

    @Test
    void 상태를_주지_않으면_400이다() throws Exception {
        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("변경할 상태를 지정하세요"));
    }

    @Test
    void 정지하면_전체_구성원에서_빠지고_해제하면_돌아온다() throws Exception {
        long everyoneTeamId = teams.findFirstByKind(TeamKind.EVERYONE).orElseThrow().getId();

        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        assertThat(teamMembers.findByTeamIdAndMemberId(everyoneTeamId, USER_ID)).isEmpty();

        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(teamMembers.findByTeamIdAndMemberId(everyoneTeamId, USER_ID)).isPresent();
    }

    @Test
    void 정지된_사용자는_자기_상태_말고는_403이다() throws Exception {
        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/org/teams").with(asUser(USER_ID, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("정지된 계정입니다"));
        mvc.perform(get("/api/org/me").with(asUser(USER_ID, "Bob")))
                .andExpect(status().isOk());
    }

    @Test
    void 비활성된_계정은_재초대로만_되돌린다() throws Exception {
        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DEACTIVATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"));

        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("비활성된 계정은 재초대로만 되돌릴 수 있습니다"));
    }

    @Test
    void 자기_계정은_비활성화할_수_없다() throws Exception {
        grants.save(GrantEntry.globalAdmin(OTHER_ADMIN_ID)); // 마지막 관리자 규칙과 분리해서 본다
        active(members, OTHER_ADMIN_ID, "Admin2");

        mvc.perform(patch("/api/org/members/" + ADMIN_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DEACTIVATED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("자기 계정은 비활성화할 수 없습니다"));
    }

    @Test
    void 마지막_전역_관리자는_내릴_수_없다() throws Exception {
        active(members, OTHER_ADMIN_ID, "Admin2");
        grants.save(GrantEntry.globalAdmin(OTHER_ADMIN_ID));

        // 둘 있을 때는 정지된다
        mvc.perform(patch("/api/org/members/" + OTHER_ADMIN_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        // 남은 한 명은 다른 관리자가 시도해도 막힌다
        grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                        SubjectType.USER, OTHER_ADMIN_ID, ResourceKind.GLOBAL, "")
                .ifPresent(grants::delete);

        mvc.perform(patch("/api/org/members/" + ADMIN_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("마지막 전역 관리자는 내릴 수 없습니다"));
    }

    @Test
    void 상세는_본인과_전역_관리자에게만_권한을_보여준다() throws Exception {
        grants.save(GrantEntry.of(SubjectType.USER, USER_ID, ResourceKind.SPACE, "sp-1", GrantRole.EDITOR));
        active(members, 300L, "Other");

        mvc.perform(get("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants[0].resourceId").value("sp-1"));

        mvc.perform(get("/api/org/members/" + USER_ID).with(asUser(300L, "Other")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants").doesNotExist())
                .andExpect(jsonPath("$.displayName").value("Bob"));
    }

    @Test
    void 상태_변경은_이력에_남는다() throws Exception {
        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/org/members/" + USER_ID + "/events").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SUSPENDED"))
                .andExpect(jsonPath("$[0].actorId").value(ADMIN_ID));
    }

    @Test
    void 상태_전이는_전역_관리자만_한다() throws Exception {
        mvc.perform(patch("/api/org/members/" + ADMIN_ID).with(asUser(USER_ID, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());
        assertThat(members.findById(ADMIN_ID).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
}
