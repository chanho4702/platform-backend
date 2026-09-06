package com.platform.orgservice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게이트웨이 상태판이 읽는 표면. 토큰 없이 200이어야 하고(프로브에는 자격증명이 없다),
 * 노출한 것은 health·info 둘뿐이어야 한다.
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
    void 헬스는_토큰_없이_200이고_DB_상세를_준다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void info도_토큰_없이_200이다() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    /**
     * health·info 밖은 열지 않는다. 노출 목록에 없어 매핑 자체가 없고, 그 앞에서 시큐리티가
     * 먼저 401로 끊는다(permitAll을 그 둘에만 줬으므로) — 어느 쪽이든 내용은 나가지 않는다.
     */
    @Test
    void 다른_액추에이터_엔드포인트는_노출되지_않는다() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
    }
}
