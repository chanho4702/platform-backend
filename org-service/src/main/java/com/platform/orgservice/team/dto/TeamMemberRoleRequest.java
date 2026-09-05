package com.platform.orgservice.team.dto;

import com.platform.orgservice.domain.TeamRole;
import jakarta.validation.constraints.NotNull;

/** 팀원 역할 변경 — LEAD | MEMBER. */
public record TeamMemberRoleRequest(@NotNull TeamRole role) {}
