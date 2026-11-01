package com.platform.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스타터의 /error 컨트롤러는 OpenAPI 문서에 나오지 않는다 — 서비스마다 숨김 설정을 다시 걸지 않아도 되게.
 * 같은 앱의 일반 컨트롤러(probe)는 문서에 남는지도 함께 본다(스펙이 통째로 비어서 통과하는 것을 막는다).
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9000/.well-known/jwks.json",
        "platform.jwt.issuer=http://localhost:9000",
        "platform.jwt.audience=platform-api"})
class PlatformErrorControllerOpenApiTest {

    @Autowired WebApplicationContext web;

    @Test
    void error_경로는_스펙에_없고_일반_경로는_있다() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        String spec = mvc.perform(get("/v3/api-docs").with(jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(spec).doesNotContain("\"/error\"");
        assertThat(spec).contains("\"/probe");
    }
}
