package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantEntry;

public record GrantDetailResponse(Long id, String subjectType, Long subjectId,
                                  String resourceType, String resourceId, String role) {
    public static GrantDetailResponse from(GrantEntry g) {
        return new GrantDetailResponse(g.getId(), g.getSubjectType().name(), g.getSubjectId(),
                g.getResourceType().name(), g.getResourceId(), g.getRole().name());
    }
}
