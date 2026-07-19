package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import jakarta.validation.constraints.NotNull;

public record GrantCreateRequest(
        @NotNull SubjectType subjectType,
        @NotNull Long subjectId,
        @NotNull ResourceKind resourceType,
        String resourceId,   // GLOBAL이면 null/'' 허용
        @NotNull GrantRole role) {}
