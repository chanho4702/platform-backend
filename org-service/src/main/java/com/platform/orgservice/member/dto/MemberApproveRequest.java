package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.TeamRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 승인. 팀·권한은 선택이며, 주지 않으면 "전체 구성원"에만 들어간 활성 사용자가 된다 —
 * 승인은 문을 열어 주는 일이고 권한 설계는 그다음이다.
 */
@Schema(description = "승인 요청. 팀·권한은 선택이며, 비우면 '전체 구성원'에만 속한 활성 사용자가 된다.")
public record MemberApproveRequest(
        @Schema(description = "승인과 함께 넣을 팀 목록") List<TeamAssignment> teams,
        @Schema(description = "승인과 함께 줄 권한 목록") List<GrantAssignment> grants) {

    @Schema(description = "팀 배정")
    public record TeamAssignment(
            @Schema(description = "팀 id", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Long teamId,
            @Schema(description = "팀 내 역할. 비우면 MEMBER.", example = "MEMBER") TeamRole role) {}

    @Schema(description = "권한 배정")
    public record GrantAssignment(
            @Schema(description = "권한 범위", example = "SPACE", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull ResourceKind scope,
            @Schema(description = "리소스 식별자. GLOBAL이면 비워 둔다.", example = "sp-1") String resourceId,
            @Schema(description = "역할", example = "EDITOR", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull GrantRole role) {}

    public List<TeamAssignment> teamsOrEmpty() { return teams == null ? List.of() : teams; }

    public List<GrantAssignment> grantsOrEmpty() { return grants == null ? List.of() : grants; }
}
