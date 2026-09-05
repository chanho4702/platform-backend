package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantEntry;

/**
 * 권한 한 줄.
 *
 * <p>{@code id}는 {@code PATCH}·{@code DELETE}의 대상이고, {@code subjectName}은 화면에 보이는 이름이다
 * (USER면 표시 이름, TEAM이면 팀 이름). 이름을 함께 주지 않으면 권한 화면이 목록을 그리려고 멤버·팀
 * 디렉터리를 통째로 다시 받아 와야 한다 — 대상이 지워진 뒤에도 행은 읽혀야 하므로 못 찾으면 id로 대신한다.
 */
public record GrantDetailResponse(Long id, String subjectType, Long subjectId, String subjectName,
                                  String resourceType, String resourceId, String role) {

    public static GrantDetailResponse from(GrantEntry g, String subjectName) {
        return new GrantDetailResponse(g.getId(), g.getSubjectType().name(), g.getSubjectId(), subjectName,
                g.getResourceType().name(), g.getResourceId(), g.getRole().name());
    }
}
