package com.platform.orgservice.invitation;

import com.jayway.jsonpath.JsonPath;
import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.InvitationStatus;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.InvitationRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.security.InternalTokenFilter;
import com.platform.orgservice.team.EveryoneTeamService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서비스 간 경로({@code /internal/org/**}). auth-server가 로그인 <b>도중</b>에 부르므로 사용자 JWT가 없다 —
 * {@code X-Internal-Token}만이 인증이고, 토큰이 없거나 다르면 403이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class InternalInvitationApiTest {

    static final String TOKEN_HEADER = "X-Internal-Token";
    static final String TOKEN = "test-internal-token";
    static final long ADMIN_ID = 100L;
    static final long INVITEE_ID = 511L;
    static final String INVITEE_EMAIL = "linkuser@test.com";

    @Autowired WebApplicationContext context;
    @Autowired InvitationRepository invitations;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
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

    private String inviteAndReturnToken() throws Exception {
        String created = mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"" + INVITEE_EMAIL + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.parse(created).read("$[0].inviteUrl", String.class);
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    void 토큰이_없거나_틀리면_403이다() throws Exception {
        String token = inviteAndReturnToken();

        mvc.perform(get("/internal/org/invitations/by-token/" + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("내부 API 토큰이 유효하지 않습니다"));

        mvc.perform(get("/internal/org/invitations/by-token/" + token).header(TOKEN_HEADER, "틀린값"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 유효한_토큰이면_초대_이메일을_알려준다() throws Exception {
        String token = inviteAndReturnToken();

        mvc.perform(get("/internal/org/invitations/by-token/" + token).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(INVITEE_EMAIL))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(get("/internal/org/invitations/by-token/없는토큰").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("유효하지 않은 초대입니다"));
    }

    /** 링크 경유 수락. 아직 org REST를 한 번도 거치지 않아 멤버 행이 없어도 여기서 만든다. */
    @Test
    void 링크_경유_수락은_멤버가_없어도_활성으로_만든다() throws Exception {
        String token = inviteAndReturnToken();
        assertThat(members.findById(INVITEE_ID)).isEmpty();

        mvc.perform(post("/internal/org/invitations/accept").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"memberId\":" + INVITEE_ID
                                + ",\"email\":\"" + INVITEE_EMAIL + "\",\"displayName\":\"링크사람\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        assertThat(members.findById(INVITEE_ID).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitations.findAll().getFirst().getAcceptedVia().name()).isEqualTo("TOKEN");
    }

    /** 링크를 얻은 사람이 다른 계정으로 들어와 남의 프리셋을 가져가면 안 된다. */
    @Test
    void 이메일이_다르면_수락되지_않는다() throws Exception {
        String token = inviteAndReturnToken();

        mvc.perform(post("/internal/org/invitations/accept").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"memberId\":" + INVITEE_ID
                                + ",\"email\":\"other@test.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.status").value("EMAIL_MISMATCH"));

        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    /** 토큰 미설정(빈 문자열)이면 헤더와 무관하게 닫힌다 — env를 빼먹은 배포가 내부 경로를 열지 않는다. */
    @Test
    void 토큰_미설정이면_내부_API는_비활성이다() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/org/invitations/by-token/x");
        request.addHeader(TOKEN_HEADER, "무엇이든");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(request, response);
    }
}
