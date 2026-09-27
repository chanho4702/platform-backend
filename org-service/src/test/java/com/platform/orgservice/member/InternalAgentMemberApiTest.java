package com.platform.orgservice.member;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberEvent;
import com.platform.orgservice.domain.MemberEventType;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asAdmin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서비스 간 에이전트 멤버 등록({@code /internal/org/members/agents}, AGP-64). agent-service가 권한 판정을
 * 끝내고 부른다 — 인증은 {@code X-Internal-Token}뿐이고, 이력 행위자는 본문의 actorId다.
 */
@SpringBootTest
@ActiveProfiles("test")
class InternalAgentMemberApiTest {

    static final String TOKEN_HEADER = "X-Internal-Token";
    static final String TOKEN = "test-internal-token";
    static final String PATH = "/internal/org/members/agents";
    static final long ADMIN_ID = 900L;
    static final long AGENT_ID = 9001L;

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberEvents.deleteAll();
        members.deleteAll();
        active(members, ADMIN_ID, "Admin");
    }

    private static String body(String displayName, Long actorId) {
        return "{\"id\":" + AGENT_ID + ",\"displayName\":\"" + displayName + "\",\"email\":\"jiho@agents.local\""
                + (actorId == null ? "" : ",\"actorId\":" + actorId) + "}";
    }

    @Test
    void 유효한_토큰이면_등록하고_공개_API와_같은_shape을_돌려준다() throws Exception {
        String internal = mvc.perform(post(PATH).header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body("지호", ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(AGENT_ID))
                .andExpect(jsonPath("$.displayName").value("지호"))
                .andExpect(jsonPath("$.email").value("jiho@agents.local"))
                .andExpect(jsonPath("$.kind").value("AGENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        // 같은 입력을 기존 공개 경로로 다시 upsert해도 응답 JSON이 같다 — shape 드리프트 방지
        String publicApi = mvc.perform(post("/api/org/members/agents").with(asAdmin(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("지호", null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(internal).isEqualTo(publicApi);

        assertThat(members.findById(AGENT_ID)).isPresent();
    }

    @Test
    void 토큰이_없거나_틀리면_403이고_등록되지_않는다() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("지호", ADMIN_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("내부 API 토큰이 유효하지 않습니다"));

        mvc.perform(post(PATH).header(TOKEN_HEADER, "틀린값")
                        .contentType(MediaType.APPLICATION_JSON).content(body("지호", ADMIN_ID)))
                .andExpect(status().isForbidden());

        // 사용자 JWT(관리자라도)로는 내부 경로를 통과할 수 없다
        mvc.perform(post(PATH).with(asAdmin(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("지호", ADMIN_ID)))
                .andExpect(status().isForbidden());

        assertThat(members.findById(AGENT_ID)).isEmpty();
    }

    @Test
    void actorId가_없으면_400이다() throws Exception {
        mvc.perform(post(PATH).header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body("지호", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("행위자 id(actorId)가 필요합니다"));

        assertThat(members.findById(AGENT_ID)).isEmpty();
    }

    @Test
    void 재등록_REACTIVATED_이력의_행위자는_본문의_actorId다() throws Exception {
        Member agent = Member.agentOf(AGENT_ID, "지호", "jiho@agents.local");
        agent.suspend(Instant.now());
        members.save(agent);

        mvc.perform(post(PATH).header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body("지호", 4242L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(members.findById(AGENT_ID).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        List<MemberEvent> events = memberEvents.findByMember(AGENT_ID, Limit.of(10));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(MemberEventType.REACTIVATED);
        assertThat(events.get(0).getActorId()).isEqualTo(4242L);
    }
}
