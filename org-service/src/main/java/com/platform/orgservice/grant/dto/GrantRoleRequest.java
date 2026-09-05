package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 권한 역할 변경 — VIEWER | COMMENTER | EDITOR | ADMIN. */
@Schema(description = "권한 역할 변경 요청")
public record GrantRoleRequest(
        @Schema(description = "바꿀 역할. 마지막 전역 ADMIN을 내리려 하면 409.", example = "ADMIN",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotNull GrantRole role) {}
