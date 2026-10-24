package com.platform.orgservice.member;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.JoinedVia;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberStatus;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 순간의 구성원 행 보장 — {@code POST /internal/org/members/ensure}(권한·계정 점검 Top 5-①).
 *
 * <p>CheckPermission이 "행 없음 = PENDING"으로 바뀌었으므로 auth-server가 로그인 성공 때 이 경로로 행을 만든다.
 * 계약: 없으면 PENDING 생성, 있으면 상태 유지(멱등), 신뢰 provider의 이메일 일치 초대·부트스트랩 관리자는 활성화.
 */
@SpringBootTest(properties = "platform.bootstrap-admin-id=777")
@ActiveProfiles("test")
class InternalMemberEnsureApiTest {

    static final String TOKEN_HEADER = "X-Internal-Token";
    static final String TOKEN = "test-internal-token";
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

    private ResultActions ensure(long id, String email, String provider) throws Exception {
        return mvc.perform(post("/internal/org/members/ensure").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":" + id + ",\"email\":\"" + email + "\",\"displayName\":\"사람" + id
                        + "\",\"provider\":\"" + provider + "\"}"));
    }

    @Test
    void 내부_토큰이_없으면_403이다() throws Exception {
        mvc.perform(post("/internal/org/members/ensure").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":1}"))
                .andExpect(status().isForbidden());
        assertThat(members.findById(1L)).isEmpty();
    }

    @Test
    void 처음_보는_사람은_PENDING으로_만들고_다시_불러도_그대로다() throws Exception {
        ensure(801L, "new@test.com", "KEYCLOAK").andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(801))
                .andExpect(jsonPath("$.status").value("PENDING"));
        ensure(801L, "new@test.com", "KEYCLOAK").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        Member m = members.findById(801L).orElseThrow();
        assertThat(m.getStatus()).isEqualTo(MemberStatus.PENDING);
        assertThat(m.getDisplayName()).isEqualTo("사람801");
        assertThat(members.count()).isEqualTo(2); // 관리자 + 801 — 중복 행 없음
    }

    /** 이미 있는 행의 상태는 바꾸지 않는다 — 정지를 로그인이 풀거나 활성을 PENDING으로 되돌리면 안 된다. */
    @Test
    void 기존_행은_상태를_바꾸지_않는다() throws Exception {
        active(members, 802L, "Active");
        Member paused = members.save(Member.of(803L, "Paused", "paused@test.com"));
        paused.suspend(Instant.now());
        members.save(paused);

        ensure(802L, "active@test.com", "KEYCLOAK").andExpect(jsonPath("$.status").value("ACTIVE"));
        ensure(803L, "paused@test.com", "GOOGLE").andExpect(jsonPath("$.status").value("SUSPENDED"));

        assertThat(members.findById(802L).orElseThrow().getJoinedVia()).isEqualTo(JoinedVia.LEGACY);
        assertThat(members.findById(803L).orElseThrow().getStatus()).isEqualTo(MemberStatus.SUSPENDED);
    }

    /** 구글(trustEmail)로 들어온 초대 대상은 로그인 순간 활성 — 위키부터 열어도 막히지 않는다. */
    @Test
    void 신뢰_provider의_이메일_일치_초대는_그_자리에서_활성화한다() throws Exception {
        mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"invited@test.com\",\"pwd@test.com\"]}"))
                .andExpect(status().isCreated());

        ensure(804L, "invited@test.com", "GOOGLE").andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(members.findById(804L).orElseThrow().getJoinedVia()).isEqualTo(JoinedVia.INVITE);

        // 비밀번호 가입(KEYCLOAK)은 이메일을 믿지 않는다 — 링크 경유만 인정(기존 규칙 그대로)
        ensure(805L, "pwd@test.com", "KEYCLOAK").andExpect(jsonPath("$.status").value("PENDING"));
    }

    /** auth-server 에이전트 관리 게이트가 묻는 판정 — 활성 + GLOBAL ADMIN grant일 때만 참, 행이 없으면 MISSING(200). */
    @Test
    void authz는_활성_전역_관리자만_참이다() throws Exception {
        Member paused = members.save(Member.of(806L, "정지관리자", "p@test.com"));
        grants.save(GrantEntry.globalAdmin(806L));
        paused.suspend(Instant.now());
        members.save(paused);
        active(members, 807L, "User");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/org/members/" + ADMIN_ID + "/authz").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.globalAdmin").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/org/members/806/authz").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.globalAdmin").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/org/members/807/authz").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.globalAdmin").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/org/members/999999/authz").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISSING"))
                .andExpect(jsonPath("$.globalAdmin").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/org/members/" + ADMIN_ID + "/authz"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 부트스트랩_관리자는_첫_로그인에_활성이다() throws Exception {
        ensure(777L, "boss@test.com", "KEYCLOAK").andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(members.findById(777L).orElseThrow().getJoinedVia()).isEqualTo(JoinedVia.BOOTSTRAP);
    }
}
