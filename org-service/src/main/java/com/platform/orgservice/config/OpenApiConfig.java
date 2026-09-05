package com.platform.orgservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * org-service의 OpenAPI 3 스펙(`GET /v3/api-docs`). UI는 붙이지 않는다 —
 * 공개 문서는 myFront가 이 JSON을 받아 `/docs/` 위키 페이지로 생성한다.
 *
 * <p>스펙은 게이트웨이·nginx가 라우팅하지 않는 경로라 클러스터 내부에서만 보인다.
 * gRPC {@code PermissionService}는 REST가 아니므로 여기 나타나지 않고,
 * {@code /internal/org/**}는 {@code springdoc.paths-to-match}로 잘라 낸다.
 */
@Configuration
public class OpenApiConfig {

    /** 공통 오류 스키마 이름. wiki·alm과 같은 이름을 쓴다 — 생성기가 서비스별로 다른 이름을 따로 다루지 않게. */
    static final String ERROR_SCHEMA = "PlatformError";

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI orgServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Org API")
                        .version("0.1.0")
                        .description("""
                                조직·멤버·팀·권한(grant) 원장. 플랫폼의 사용자와 그 사람이 무엇을 할 수 있는지를 \
                                여기 한 곳에서 관리하고, wiki·alm은 이 판정을 gRPC로 받아 쓴다."""))
                .servers(List.of(new Server().url("/")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("개인 API 토큰 `chanho_pat_…` 또는 세션 JWT"))
                        .addSchemas(ERROR_SCHEMA, errorSchema()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * 코드가 실제로 내는 공통 오류를 모든 오퍼레이션에 붙인다.
     *
     * <p>401·403은 어디서나 난다 — 이 서비스에는 공개 엔드포인트가 없고(SecurityConfig),
     * 세부 인가는 {@code PermissionFacade}가 판정해 403을 던진다.
     * 404는 경로 변수를 받는 오퍼레이션에만 붙인다(대상이 있어야 지목할 수 있으므로).
     * 400은 본문을 받는 오퍼레이션에만 붙인다(bean validation 실패가 그리로 온다).
     * 409는 사유가 엔드포인트마다 다르므로 {@link ConflictResponse}를 붙인 곳에만 그 사유로 넣는다.
     * 응답 본문은 common-starter의 {@code {"error": 메시지}} 계약 그대로다.
     */
    @Bean
    OperationCustomizer platformErrorResponses() {
        return (Operation operation, HandlerMethod handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            putIfAbsent(responses, "401", "인증되지 않았습니다 — 토큰이 없거나 만료됐습니다.");
            putIfAbsent(responses, "403", "권한이 없습니다.");
            if (hasPathVariable(handlerMethod)) {
                putIfAbsent(responses, "404", "대상을 찾을 수 없습니다.");
            }
            if (takesRequestBody(handlerMethod)) {
                putIfAbsent(responses, "400", "요청이 올바르지 않습니다 — 필수 값 누락이나 규칙 위반.");
            }
            ConflictResponse conflict = handlerMethod.getMethodAnnotation(ConflictResponse.class);
            if (conflict != null) {
                putIfAbsent(responses, "409", conflict.value());
            }
            return operation;
        };
    }

    private static boolean hasPathVariable(HandlerMethod handlerMethod) {
        for (var parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(PathVariable.class)) return true;
        }
        return false;
    }

    /** 본문(JSON 또는 업로드 파일)을 받는가 — 그렇다면 검증 실패로 400이 난다. */
    private static boolean takesRequestBody(HandlerMethod handlerMethod) {
        for (var parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestBody.class)) return true;
            if (MultipartFile.class.isAssignableFrom(parameter.getParameterType())) return true;
        }
        return false;
    }

    private static void putIfAbsent(ApiResponses responses, String status, String description) {
        if (responses.containsKey(status)) return;
        responses.addApiResponse(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA)))));
    }

    private static Schema<?> errorSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setDescription("오류 응답. 메시지는 한국어이며 화면에 그대로 노출된다.");
        schema.addProperty("error", new StringSchema()
                .description("사용자에게 보여 줄 오류 메시지")
                .example("권한이 없습니다"));
        schema.setRequired(List.of("error"));
        return schema;
    }
}
