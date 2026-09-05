package com.platform.orgservice.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgentRegisterRequest(
        @NotNull Long id,
        @NotBlank String displayName,
        String email) {}
