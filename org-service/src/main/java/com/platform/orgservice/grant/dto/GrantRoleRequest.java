package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantRole;
import jakarta.validation.constraints.NotNull;

/** 권한 역할 변경 — VIEWER | COMMENTER | EDITOR | ADMIN. */
public record GrantRoleRequest(@NotNull GrantRole role) {}
