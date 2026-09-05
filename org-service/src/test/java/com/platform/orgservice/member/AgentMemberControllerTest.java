package com.platform.orgservice.member;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.repository.MemberRepository;
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
import static com.platform.orgservice.TestAuth.asAdmin;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AgentMemberControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    MockMvc mvc;

    static final long ADMIN_ID = 900L;
    static final long AGENT_ID = 9001L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        members.deleteAll();
        // U1부터 처음 보는 사용자는 PENDING으로 격리된다 — 등록 호출자는 활성 사용자여야 한다
        active(members, ADMIN_ID, "Admin");
        active(members, 800L, "Bob");
    }

    /** POST /api/org/members/agents {"id":9001,"displayName":"지호"} ADMIN → 200 kind=AGENT, 반복 호출도 idempotent. */
    @Test
    void admin_registers_agent_member_idempotently() throws Exception {
        String body = "{\"id\":" + AGENT_ID + ",\"displayName\":\"지호\"}";

        mvc.perform(post("/api/org/members/agents").with(asAdmin(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(AGENT_ID))
                .andExpect(jsonPath("$.displayName").value("지호"))
                .andExpect(jsonPath("$.kind").value("AGENT"));

        String body2 = "{\"id\":" + AGENT_ID + ",\"displayName\":\"지호2\"}";
        mvc.perform(post("/api/org/members/agents").with(asAdmin(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(AGENT_ID))
                .andExpect(jsonPath("$.displayName").value("지호2"))
                .andExpect(jsonPath("$.kind").value("AGENT"));

        assertThat(members.findById(AGENT_ID)).isPresent();
        assertThat(members.findById(AGENT_ID).get().getDisplayName()).isEqualTo("지호2");
    }

    @Test
    void non_admin_forbidden() throws Exception {
        String body = "{\"id\":" + AGENT_ID + ",\"displayName\":\"지호\"}";

        mvc.perform(post("/api/org/members/agents").with(asUser(800L, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    /** kind=AGENT인 member가 있을 때 같은 id로 인증된 GET 호출이 들어와도 MemberMirrorFilter가 덮어쓰지 않는다. */
    @Test
    void mirror_filter_skips_agent_rows() throws Exception {
        members.save(Member.agentOf(AGENT_ID, "페르소나이름", "persona@agents.local"));

        mvc.perform(get("/api/org/members").with(asUser(AGENT_ID, "실제이름다름")))
                .andExpect(status().isOk());

        Member reloaded = members.findById(AGENT_ID).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("페르소나이름");
        assertThat(reloaded.getEmail()).isEqualTo("persona@agents.local");
    }

    /**
     * GET /api/org/members → 각 항목에 kind.
     *
     * 기본 필터가 ACTIVE·HUMAN이라 에이전트를 함께 보려면 kind=ALL을 명시한다 —
     * 사람 고르는 화면에 페르소나가 섞이지 않게 한 것이 기본값의 취지다.
     */
    @Test
    void member_list_includes_kind() throws Exception {
        members.save(Member.of(700L, "사람", "human@test.com"));
        members.save(Member.agentOf(AGENT_ID, "에이전트", "agent@test.com"));

        String body = mvc.perform(get("/api/org/members?kind=ALL").with(asUser(700L, "사람")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<java.util.Map<String, Object>> list = com.jayway.jsonpath.JsonPath.parse(body).read("$");
        java.util.Map<Long, String> kindById = new java.util.HashMap<>();
        for (var entry : list) {
            kindById.put(((Number) entry.get("id")).longValue(), (String) entry.get("kind"));
        }
        assertThat(kindById.get(700L)).isEqualTo("HUMAN");
        assertThat(kindById.get(AGENT_ID)).isEqualTo("AGENT");
    }
}
