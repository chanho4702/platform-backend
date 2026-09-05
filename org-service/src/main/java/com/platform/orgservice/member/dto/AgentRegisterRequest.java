package com.platform.orgservice.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "에이전트 페르소나 등록 요청. 로그인하지 않는 계정이라 JIT 미러링으로는 생기지 않는다.")
public record AgentRegisterRequest(
        @Schema(description = "에이전트 멤버 id. 사람 멤버와 같은 id 공간을 쓴다.", example = "9001",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long id,
        @Schema(description = "표시 이름", example = "리뷰 봇",
                requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String displayName,
        @Schema(description = "이메일. 에이전트는 없어도 된다.", example = "review-bot@example.com") String email) {}
