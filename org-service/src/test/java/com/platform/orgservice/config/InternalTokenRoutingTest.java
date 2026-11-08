package com.platform.orgservice.config;

import com.platform.orgservice.authsync.AuthAccountClient;
import com.platform.orgservice.mail.FakeMailConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * N5 — 인바운드 {@code /internal/org/**}의 경로별 토큰 집합과 아웃바운드 org→auth 토큰 분리.
 *
 * <ul>
 *   <li>identity(초대·ensure·authz) ← {@code ORG_IDENTITY_TOKEN}</li>
 *   <li>mail ← {@code ORG_MAIL_TOKEN} <b>과</b> {@code ORG_AGENT_TOKEN}(agent-service는 env 하나로 두 경로를 부른다)</li>
 *   <li>agents ← {@code ORG_AGENT_TOKEN}</li>
 *   <li>새 변수가 없거나 <b>빈 문자열</b>이면 옛 값({@code platform.org.internal-token} = {@code ORG_INTERNAL_TOKEN})</li>
 * </ul>
 *
 * 통과 판정은 "403이 아님"이다 — 본문이 비어 400·404가 나도 토큰 게이트는 지난 것이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeMailConfig.class)
class InternalTokenRoutingTest {

    static final String H = "X-Internal-Token";
    /** application-test.yml의 platform.org.internal-token — 옛 공유 토큰(ORG_INTERNAL_TOKEN) 자리. */
    static final String LEGACY = "test-internal-token";

    @Autowired WebApplicationContext context;
    @Autowired AuthAccountClient authAccountClient;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // 실제 컨트롤러 매핑 전부(InternalMailController·InternalAgentMemberController·InternalInvitationController·
    // InternalMemberEnsureController·InternalMemberAuthzController)
    static MockHttpServletRequestBuilder mailSend() {
        return post("/internal/org/mail").contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    static MockHttpServletRequestBuilder mailStatus() {
        return get("/internal/org/mail/status");
    }

    static MockHttpServletRequestBuilder agents() {
        return post("/internal/org/members/agents").contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    static MockHttpServletRequestBuilder invitationByToken() {
        return get("/internal/org/invitations/by-token/nope");
    }

    static MockHttpServletRequestBuilder invitationAccept() {
        return post("/internal/org/invitations/accept").contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    static MockHttpServletRequestBuilder ensure() {
        return post("/internal/org/members/ensure").contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    static MockHttpServletRequestBuilder authz() {
        return get("/internal/org/members/42/authz");
    }

    int status(MockHttpServletRequestBuilder req, String token) throws Exception {
        if (token != null) req.header(H, token);
        MvcResult r = mvc.perform(req).andReturn();
        if (r.getResponse().getStatus() == 403) {
            assertThat(r.getResponse().getContentAsString()).contains("\"error\"");
        }
        return r.getResponse().getStatus();
    }

    void allowed(MockHttpServletRequestBuilder req, String token) throws Exception {
        assertThat(status(req, token)).as("통과해야 함: token=%s", token).isNotEqualTo(403);
    }

    void denied(MockHttpServletRequestBuilder req, String token) throws Exception {
        assertThat(status(req, token)).as("403이어야 함: token=%s", token).isEqualTo(403);
    }

    /** 새 변수 없음 — 옛 값 하나가 세 경로 전부를 연다(배포만으로 동작 불변). */
    @Test
    void 새_변수가_없으면_옛_토큰이_모든_경로를_연다() throws Exception {
        for (MockHttpServletRequestBuilder req : new MockHttpServletRequestBuilder[]{
                mailSend(), mailStatus(), agents(), invitationByToken(), invitationAccept(), ensure(), authz()}) {
            allowed(req, LEGACY);
        }
        denied(mailStatus(), "other");
        denied(authz(), null);
        assertThat(authAccountClient.token()).isEqualTo(LEGACY);
    }

    @Nested
    @TestPropertySource(properties = {
            "ORG_IDENTITY_TOKEN=id-tok",
            "ORG_MAIL_TOKEN=mail-tok",
            "ORG_AGENT_TOKEN=agent-tok",
            "AUTH_ACCOUNT_SYNC_TOKEN=sync-tok"})
    class 모두_분리 {

        @Test
        void mail_경로는_MAIL과_AGENT_토큰만_통과한다() throws Exception {
            allowed(mailSend(), "mail-tok");
            allowed(mailStatus(), "agent-tok");
            denied(mailSend(), "id-tok");
            denied(mailStatus(), LEGACY);
            denied(mailStatus(), "sync-tok");
        }

        @Test
        void agents_경로는_AGENT_토큰만_통과한다() throws Exception {
            allowed(agents(), "agent-tok");
            denied(agents(), "mail-tok");
            denied(agents(), "id-tok");
            denied(agents(), LEGACY);
        }

        @Test
        void identity_경로는_IDENTITY_토큰만_통과한다() throws Exception {
            for (MockHttpServletRequestBuilder req : new MockHttpServletRequestBuilder[]{
                    invitationByToken(), invitationAccept(), ensure(), authz()}) {
                allowed(req, "id-tok");
            }
            denied(invitationByToken(), "mail-tok");
            denied(invitationAccept(), "agent-tok");
            denied(ensure(), LEGACY);
            denied(authz(), "agent-tok");
            denied(authz(), "sync-tok");
        }

        @Test
        void 매핑_안_된_내부_경로는_어느_토큰이든_403이다() throws Exception {
            for (String t : new String[]{"id-tok", "mail-tok", "agent-tok", LEGACY}) {
                denied(get("/internal/org/unknown"), t);
            }
        }

        @Test
        void 아웃바운드_auth_동기화는_전용_토큰을_싣는다() {
            assertThat(authAccountClient.token()).isEqualTo("sync-tok");
        }
    }

    /** compose가 새 변수를 빈 문자열로 넘겨도(`${ORG_IDENTITY_TOKEN:-}`) 옛 값으로 폴백해야 한다. */
    @Nested
    @TestPropertySource(properties = {
            "ORG_IDENTITY_TOKEN=",
            "ORG_MAIL_TOKEN=",
            "ORG_AGENT_TOKEN=  ",
            "AUTH_ACCOUNT_SYNC_TOKEN="})
    class 새_변수가_빈_문자열 {

        @Test
        void 세_경로_모두_옛_토큰으로_열리고_아웃바운드도_옛_토큰이다() throws Exception {
            allowed(mailStatus(), LEGACY);
            allowed(agents(), LEGACY);
            allowed(invitationByToken(), LEGACY);
            allowed(authz(), LEGACY);
            denied(mailStatus(), "");
            assertThat(authAccountClient.token()).isEqualTo(LEGACY);
        }
    }

    /** AGENT만 먼저 분리 — 위키·ALM은 아직 옛 값으로 메일을 보내므로 mail은 옛 값도 받아야 한다. */
    @Nested
    @TestPropertySource(properties = "ORG_AGENT_TOKEN=agent-tok")
    class AGENT만_분리 {

        @Test
        void mail은_AGENT와_옛_토큰을_agents는_AGENT만_identity는_옛_토큰을_받는다() throws Exception {
            allowed(mailSend(), "agent-tok");
            allowed(mailSend(), LEGACY);
            allowed(agents(), "agent-tok");
            denied(agents(), LEGACY);
            allowed(ensure(), LEGACY);
            denied(ensure(), "agent-tok");
        }
    }

    /** 무중단 회전 — 수신에 `신,구`를 넣어 두면 둘 다 통과한다. */
    @Nested
    @TestPropertySource(properties = "ORG_IDENTITY_TOKEN=id-new, id-old")
    class 다중_토큰 {

        @Test
        void 신_구_둘_다_통과하고_다른_값은_403이다() throws Exception {
            allowed(authz(), "id-new");
            allowed(authz(), "id-old");
            denied(authz(), LEGACY);
            denied(authz(), "id-new, id-old");
        }
    }

    /** 옛 값도 새 값도 없으면 내부 API 전체가 닫힌다(fail-closed) — 아웃바운드도 부르지 않는다. */
    @Nested
    @TestPropertySource(properties = "platform.org.internal-token=")
    class 모두_비면 {

        @Test
        void 모든_내부_경로가_403이고_auth_동기화는_미구성이다() throws Exception {
            for (MockHttpServletRequestBuilder req : new MockHttpServletRequestBuilder[]{
                    mailSend(), mailStatus(), agents(), invitationByToken(), invitationAccept(), ensure(), authz()}) {
                denied(req, "");
                denied(req, LEGACY);
            }
            assertThat(authAccountClient.isConfigured()).isFalse();
        }
    }
}
