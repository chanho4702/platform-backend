package com.platform.orgservice.team.dto;

import com.platform.orgservice.domain.TeamRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 팀원 역할 변경 — LEAD | MEMBER. */
@Schema(description = "팀원 역할 변경 요청")
public record TeamMemberRoleRequest(
        @Schema(description = "바꿀 역할 — LEAD(리더) | MEMBER", example = "LEAD",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotNull TeamRole role) {}
