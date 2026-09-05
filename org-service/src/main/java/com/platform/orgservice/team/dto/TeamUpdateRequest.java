package com.platform.orgservice.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "팀 수정 요청")
public record TeamUpdateRequest(
        @Schema(description = "팀 이름. 100자까지.", example = "플랫폼팀",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 100) String name,
        @Schema(description = "팀 설명", example = "게이트웨이·인증·조직 서비스를 만든다") String description) {}
