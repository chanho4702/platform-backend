package com.platform.common;

import com.platform.common.security.PlatformResourceServerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스타터 계약 — 디코더는 우리 것(audience 검증), 오류 본문은 {"error": 메시지}. */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9000/.well-known/jwks.json",
        "platform.jwt.issuer=http://localhost:9000",
        "platform.jwt.audience=platform-api"})
class StarterContractTest {

    @Autowired ConfigurableApplicationContext context;
    @Autowired WebApplicationContext web;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void 디코더는_스타터가_만든_것이다() {
        // Boot 자동 구성이 먼저 만들면 audience 검증이 조용히 빠진다 — 팩토리가 우리 클래스인지로 확인
        String factory = context.getBeanFactory().getBeanDefinition("jwtDecoder").getFactoryBeanName();
        assertThat(factory).contains(PlatformResourceServerAutoConfiguration.class.getSimpleName().toLowerCase().substring(0, 8));
    }

    @Test
    void 오류_본문은_플랫폼_계약이다() throws Exception {
        mvc.perform(get("/probe/missing").with(jwt())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("없음"));
        mvc.perform(get("/probe/conflict").with(jwt())).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("충돌"));
        mvc.perform(get("/probe/bad").with(jwt())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("잘못됨"));
        mvc.perform(post("/probe/valid").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("이름은 비울 수 없습니다"));
        mvc.perform(post("/probe/valid").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("요청 값이 올바르지 않습니다"));
    }
}
