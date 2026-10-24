package com.platform.orgservice.invitation;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.InvitationRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리소스 ADMIN 초대 경로를 <b>켠</b> 설치(ORG_INVITATION_RESOURCE_ADMIN=true)와 초대 도메인 허용 목록
 * (ORG_INVITATION_ALLOWED_DOMAINS) — 권한·계정 점검 Top 5-②. 기본(끔·제한 없음)은 {@link InvitationFlowTest}.
 */
@SpringBootTest(properties = {
        "platform.org.invitation.resource-admin-enabled=true",
        "platform.org.invitation.allowed-domains= Test.com , @corp.example "
})
@ActiveProfiles("test")
class InvitationResourceAdminEnabledTest {

    static final long ADMIN_ID = 100L;

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired InvitationRepository invitations;
    @Autowired GrantEntryRepository grants;
    @Autowired EveryoneTeamService everyone;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberEvents.deleteAll();
        invitations.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        everyone.ensure();
    }

    /** 켠 설치에서는 리소스 ADMIN도 초대할 수 있지만, 프리셋은 자기가 관리하는 리소스로만 좁혀진다. */
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

        mvc.perform(get("/api/org/invitations/policy").with(asUser(602L, "SpaceAdmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canInvite").value(true))
                .andExpect(jsonPath("$.resourceAdminEnabled").value(true));
    }

    /** 허용 목록 밖 도메인은 전역 관리자라도 400이고, 아무 초대도 만들어지지 않는다(대소문자·앞 '@' 무시). */
    @Test
    void 허용_도메인_밖이면_전역_관리자도_초대할_수_없다() throws Exception {
        mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"ok@TEST.com\",\"outsider@gmail.com\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith(
                        "초대할 수 없는 이메일 도메인입니다: outsider@gmail.com")));
        assertThat(invitations.findAll()).isEmpty(); // 한 요청은 통째로 — 앞의 허용 주소도 남지 않는다

        mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"ok@test.com\",\"lead@corp.example\"]}"))
                .andExpect(status().isCreated());
        assertThat(invitations.findAll()).hasSize(2);

        // 하위 도메인은 정확히 일치하지 않으므로 거부된다
        mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"x@sub.test.com\"]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/org/invitations/policy").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedDomains[0]").value("test.com"))
                .andExpect(jsonPath("$.allowedDomains[1]").value("corp.example"));
    }
}
