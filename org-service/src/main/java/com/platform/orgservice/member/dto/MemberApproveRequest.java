package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.TeamRole;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 승인. 팀·권한은 선택이며, 주지 않으면 "전체 구성원"에만 들어간 활성 사용자가 된다 —
 * 승인은 문을 열어 주는 일이고 권한 설계는 그다음이다.
 */
public record MemberApproveRequest(List<TeamAssignment> teams, List<GrantAssignment> grants) {

    public record TeamAssignment(@NotNull Long teamId, TeamRole role) {}

    public record GrantAssignment(@NotNull ResourceKind scope, String resourceId, @NotNull GrantRole role) {}

    public List<TeamAssignment> teamsOrEmpty() { return teams == null ? List.of() : teams; }

    public List<GrantAssignment> grantsOrEmpty() { return grants == null ? List.of() : grants; }
}
