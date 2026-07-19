package com.platform.orgservice.team.dto;

import com.platform.orgservice.domain.Team;

public record TeamResponse(Long id, String name, String description) {
    public static TeamResponse from(Team t) {
        return new TeamResponse(t.getId(), t.getName(), t.getDescription());
    }
}
