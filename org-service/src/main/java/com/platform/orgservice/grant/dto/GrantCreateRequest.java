package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "권한 부여 요청")
public record GrantCreateRequest(
        @Schema(description = "권한 주체 종류 — USER(사람) | TEAM(팀)", example = "USER",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotNull SubjectType subjectType,
        @Schema(description = "권한 주체 id — 멤버 id 또는 팀 id", example = "42",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long subjectId,
        @Schema(description = "리소스 종류", example = "SPACE",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ResourceKind resourceType,
        @Schema(description = "리소스 식별자. GLOBAL이면 null이나 빈 문자열로 둔다.", example = "sp-1")
        String resourceId,   // GLOBAL이면 null/'' 허용
        @Schema(description = "역할. ADMIN ⊃ EDITOR ⊃ COMMENTER ⊃ VIEWER.", example = "EDITOR",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotNull GrantRole role) {}
