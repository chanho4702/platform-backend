package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantEntry;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 권한 한 줄.
 *
 * <p>{@code id}는 {@code PATCH}·{@code DELETE}의 대상이고, {@code subjectName}은 화면에 보이는 이름이다
 * (USER면 표시 이름, TEAM이면 팀 이름). 이름을 함께 주지 않으면 권한 화면이 목록을 그리려고 멤버·팀
 * 디렉터리를 통째로 다시 받아 와야 한다 — 대상이 지워진 뒤에도 행은 읽혀야 하므로 못 찾으면 id로 대신한다.
 */
@Schema(description = "권한 한 줄")
public record GrantDetailResponse(
        @Schema(description = "권한 행 id. 역할 변경·회수의 대상이다.", example = "17") Long id,
        @Schema(description = "권한 주체 종류 — USER(사람) | TEAM(팀)", example = "USER",
                allowableValues = {"USER", "TEAM"}) String subjectType,
        @Schema(description = "권한 주체 id — 멤버 id 또는 팀 id", example = "42") Long subjectId,
        @Schema(description = "화면에 보이는 주체 이름. 주체가 지워졌으면 id 문자열로 대신한다.",
                example = "김찬호") String subjectName,
        @Schema(description = "리소스 종류", example = "SPACE",
                allowableValues = {"GLOBAL", "SPACE", "PROJECT"}) String resourceType,
        @Schema(description = "리소스 식별자. GLOBAL이면 null이거나 빈 문자열.", example = "sp-1") String resourceId,
        @Schema(description = "역할. ADMIN ⊃ EDITOR ⊃ COMMENTER ⊃ VIEWER.", example = "EDITOR",
                allowableValues = {"VIEWER", "COMMENTER", "EDITOR", "ADMIN"}) String role) {

    public static GrantDetailResponse from(GrantEntry g, String subjectName) {
        return new GrantDetailResponse(g.getId(), g.getSubjectType().name(), g.getSubjectId(), subjectName,
                g.getResourceType().name(), g.getResourceId(), g.getRole().name());
    }
}
