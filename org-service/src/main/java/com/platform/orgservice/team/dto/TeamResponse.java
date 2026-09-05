package com.platform.orgservice.team.dto;

import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamRole;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 팀 한 줄. {@code kind}·{@code memberCount}·{@code myRole}은 확장 필드다 —
 * 기존 필드({@code id}·{@code name}·{@code description})의 의미는 그대로다.
 * {@code myRole}은 호출자가 그 팀 소속이 아니면 null이다.
 */
@Schema(description = "팀 한 줄")
public record TeamResponse(
        @Schema(description = "팀 id", example = "3") Long id,
        @Schema(description = "팀 이름", example = "플랫폼팀") String name,
        @Schema(description = "팀 설명", example = "게이트웨이·인증·조직 서비스를 만든다") String description,
        @Schema(description = "팀 종류 — EVERYONE은 활성 사람 멤버 전원이 자동으로 속하며 가입·탈퇴·삭제할 수 없다",
                example = "STANDARD", allowableValues = {"STANDARD", "EVERYONE"}) String kind,
        @Schema(description = "팀원 수", example = "7") long memberCount,
        @Schema(description = "호출자의 팀 내 역할. 그 팀 소속이 아니면 null.", example = "LEAD",
                allowableValues = {"LEAD", "MEMBER"}) String myRole) {

    public static TeamResponse of(Team t, long memberCount, TeamRole myRole) {
        return new TeamResponse(t.getId(), t.getName(), t.getDescription(),
                t.getKind().name(), memberCount, myRole == null ? null : myRole.name());
    }

    /** 구성원 수를 함께 세지 않는 자리(단건 응답 등). */
    public static TeamResponse from(Team t) {
        return of(t, 0L, null);
    }
}
