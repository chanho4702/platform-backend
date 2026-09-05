package com.platform.orgservice.team.dto;

import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamRole;

/**
 * 팀 한 줄. {@code kind}·{@code memberCount}·{@code myRole}은 확장 필드다 —
 * 기존 필드({@code id}·{@code name}·{@code description})의 의미는 그대로다.
 * {@code myRole}은 호출자가 그 팀 소속이 아니면 null이다.
 */
public record TeamResponse(Long id, String name, String description,
                           String kind, long memberCount, String myRole) {

    public static TeamResponse of(Team t, long memberCount, TeamRole myRole) {
        return new TeamResponse(t.getId(), t.getName(), t.getDescription(),
                t.getKind().name(), memberCount, myRole == null ? null : myRole.name());
    }

    /** 구성원 수를 함께 세지 않는 자리(단건 응답 등). */
    public static TeamResponse from(Team t) {
        return of(t, 0L, null);
    }
}
