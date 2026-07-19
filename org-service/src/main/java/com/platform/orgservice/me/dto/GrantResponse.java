package com.platform.orgservice.me.dto;

import com.platform.orgservice.domain.GrantEntry;

public record GrantResponse(String resourceType, String resourceId, String role) {
    public static GrantResponse from(GrantEntry g) {
        return new GrantResponse(g.getResourceType().name(), g.getResourceId(), g.getRole().name());
    }
}
