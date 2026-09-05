package com.platform.orgservice.invitation.dto;

import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.TeamRole;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 초대 생성. 이메일 여러 개를 한 번에 받는 이유는 화면이 붙여넣기로 여러 줄을 받기 때문이다 —
 * 한 명씩 보내면 같은 팀·권한 프리셋을 매번 다시 고르게 된다.
 */
public record InvitationCreateRequest(
        @NotEmpty(message = "초대할 이메일을 입력하세요") List<String> emails,
        List<TeamPreset> teams,
        List<GrantPreset> grants,
        @Size(max = 500, message = "메시지는 500자까지 입력할 수 있습니다") String message) {

    public record TeamPreset(@NotNull Long teamId, TeamRole role) {}

    /** GLOBAL이면 resourceId는 비워 둔다(grant_entry와 같은 표현). */
    public record GrantPreset(@NotNull ResourceKind scope, String resourceId, @NotNull GrantRole role) {}

    public List<TeamPreset> teamsOrEmpty() { return teams == null ? List.of() : teams; }

    public List<GrantPreset> grantsOrEmpty() { return grants == null ? List.of() : grants; }
}
