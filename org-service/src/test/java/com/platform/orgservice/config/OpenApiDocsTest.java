package com.platform.orgservice.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 스펙이 문서 생성기(myFront `scripts/api/`)가 쓸 수 있는 모양인지 검증한다.
 *
 * <p>여기서 막는 사고는 둘이다 — 태그·요약 없는 오퍼레이션이 들어와 문서 페이지에 이름 없는 항목이
 * 생기는 것, 그리고 내부 전용 경로({@code /internal/**}·액추에이터)가 공개 문서로 새는 것.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenApiDocsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "patch", "options", "head", "trace");

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** 토큰 없이 200 — 생성기는 인증 없이 스펙만 받아 간다(SecurityConfig의 permitAll). */
    private JsonNode spec() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    @Test
    void 스펙이_인증_없이_200이고_제목이_붙어_있다() throws Exception {
        JsonNode spec = spec();

        assertThat(spec.path("info").path("title").asText()).isEqualTo("Org API");
        assertThat(spec.path("info").path("version").asText()).isNotBlank();
        assertThat(spec.path("info").path("description").asText()).isNotBlank();
        assertThat(spec.path("servers").get(0).path("url").asText()).isEqualTo("/");
    }

    @Test
    void 모든_오퍼레이션에_태그와_요약이_있다() throws Exception {
        List<String> missing = new ArrayList<>();
        int operations = 0;

        for (Map.Entry<String, JsonNode> path : spec().path("paths").properties()) {
            for (String method : METHODS) {
                JsonNode op = path.getValue().get(method);
                if (op == null) continue;
                operations++;
                String where = method.toUpperCase() + " " + path.getKey();
                if (!op.path("tags").isArray() || op.path("tags").isEmpty()) missing.add(where + " — tags 없음");
                if (op.path("summary").asText("").isBlank()) missing.add(where + " — summary 없음");
            }
        }

        assertThat(operations).isPositive();
        assertThat(missing).isEmpty();
    }

    @Test
    void 내부_전용_경로와_액추에이터는_문서에_없다() throws Exception {
        List<String> leaked = new ArrayList<>();

        for (Map.Entry<String, JsonNode> path : spec().path("paths").properties()) {
            String p = path.getKey();
            if (p.startsWith("/internal") || p.startsWith("/actuator") || p.startsWith("/v3/api-docs")) {
                leaked.add(p);
            }
            if (!p.startsWith("/api/org/")) leaked.add(p);
        }

        assertThat(leaked).isEmpty();
    }

    @Test
    void bearerAuth_보안스킴이_전역으로_걸려_있다() throws Exception {
        JsonNode spec = spec();

        JsonNode scheme = spec.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("description").asText()).contains("chanho_pat_");

        boolean globalBearer = false;
        for (JsonNode requirement : spec.path("security")) {
            if (requirement.has("bearerAuth")) globalBearer = true;
        }
        assertThat(globalBearer).isTrue();
    }

    @Test
    void 공통_오류_응답이_모든_오퍼레이션에_붙는다() throws Exception {
        JsonNode spec = spec();
        List<String> missing = new ArrayList<>();

        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            boolean variablePath = path.getKey().contains("{");
            for (String method : METHODS) {
                JsonNode op = path.getValue().get(method);
                if (op == null) continue;
                JsonNode responses = op.path("responses");
                String where = method.toUpperCase() + " " + path.getKey();
                if (!responses.has("401")) missing.add(where + " — 401 없음");
                if (!responses.has("403")) missing.add(where + " — 403 없음");
                if (variablePath && !responses.has("404")) missing.add(where + " — 404 없음");
            }
        }

        assertThat(missing).isEmpty();
        JsonNode error = spec.path("components").path("schemas").path("PlatformError");
        assertThat(error.path("type").asText()).isEqualTo("object");
        assertThat(error.path("properties").path("error").path("type").asText()).isEqualTo("string");
    }

    /**
     * 409는 실제로 충돌을 내는 곳에만 붙어야 한다 — 전부에 붙이면 "여기서 충돌이 난다"는 뜻이 사라진다.
     * 마지막 전역 관리자 강등(권한 역할 변경)이 그 대표 사례다.
     */
    @Test
    void 충돌을_내는_오퍼레이션에만_409가_붙는다() throws Exception {
        JsonNode paths = spec().path("paths");

        assertThat(paths.path("/api/org/grants/{id}").path("patch").path("responses").has("409")).isTrue();
        assertThat(paths.path("/api/org/grants").path("get").path("responses").has("409")).isFalse();
        assertThat(paths.path("/api/org/teams").path("get").path("responses").has("409")).isFalse();
    }

    /** 성공 응답이 사라지지 않았는지 — @ApiResponse를 손으로 붙이면 springdoc이 자동 200을 빼 버린다. */
    @Test
    void 모든_오퍼레이션에_성공_응답이_있다() throws Exception {
        List<String> missing = new ArrayList<>();

        for (Map.Entry<String, JsonNode> path : spec().path("paths").properties()) {
            for (String method : METHODS) {
                JsonNode op = path.getValue().get(method);
                if (op == null) continue;
                boolean success = false;
                for (String status : (Iterable<String>) () -> op.path("responses").fieldNames()) {
                    if (status.startsWith("2")) success = true;
                }
                if (!success) missing.add(method.toUpperCase() + " " + path.getKey());
            }
        }

        assertThat(missing).isEmpty();
    }
}
