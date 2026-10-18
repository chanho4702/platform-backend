package com.platform.common.web;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 어드바이스가 못 잡은 것(미처리 예외·405·415·@ResponseStatus 예외)이 서블릿 오류 디스패치로
 * /error에 올 때도 본문은 {"error"}다(P-11). MockMvc는 오류 디스패치를 하지 않으므로 컨테이너가
 * 채우는 요청 속성을 직접 넣어 /error를 부른다.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9000/.well-known/jwks.json",
        "platform.jwt.issuer=http://localhost:9000",
        "platform.jwt.audience=platform-api"})
class PlatformErrorControllerTest {

    @Autowired WebApplicationContext web;
    @Autowired ErrorController errorController;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void Boot_기본_오류_컨트롤러_대신_플랫폼_것이_선다() {
        assertThat(errorController).isInstanceOf(PlatformErrorController.class);
    }

    @Test
    void 미처리_예외는_500_고정_문구이고_원인은_본문에_없다() throws Exception {
        mvc.perform(get("/error").with(jwt())
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("db password=secret"))
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "db password=secret"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("일시적인 오류가 발생했습니다"))
                .andExpect(jsonPath("$.timestamp").doesNotExist());
    }

    @Test
    void 영어_사유의_4xx는_상태별_한국어_문구다() throws Exception {
        mvc.perform(get("/error").with(jwt())
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 405)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "Request method PATCH is not supported"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("지원하지 않는 요청 방식입니다"));
        mvc.perform(get("/error").with(jwt()).requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("요청한 대상을 찾을 수 없습니다"));
    }

    @Test
    void 서비스가_한국어로_쓴_사유는_그대로_보인다() throws Exception {
        mvc.perform(get("/error").with(jwt())
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 413)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "첨부는 20MB까지입니다"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error").value("첨부는 20MB까지입니다"));
    }

    /** Codex P2 — 클라이언트가 JSON을 받지 않겠다고 해도(406 경로 등) 이 컨트롤러가 선택되고 본문은 JSON이다. */
    @Test
    void JSON이_아닌_Accept여도_플랫폼_컨트롤러가_답한다() throws Exception {
        mvc.perform(get("/error").with(jwt()).accept(org.springframework.http.MediaType.APPLICATION_XML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 406))
                .andExpect(status().isNotAcceptable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("지원하지 않는 응답 형식입니다"));
    }
}
