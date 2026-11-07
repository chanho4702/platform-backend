package com.platform.searchservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내부망 전용 actuator 표면. info(상태판)와 prometheus(Alloy 스크레이프)는 토큰 없이 열리고,
 * 노출 목록 밖은 시큐리티가 401로 끊는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActuatorEndpointTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void prometheus는_토큰_없이_200이고_서비스_태그가_붙는다() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("application=\"search-service\"")));
    }

    @Test
    void info는_토큰_없이_200이다() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void 다른_액추에이터_엔드포인트는_노출되지_않는다() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
