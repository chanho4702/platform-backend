package com.platform.orgservice.invitation.dto;

import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.TeamRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 초대 생성. 이메일 여러 개를 한 번에 받는 이유는 화면이 붙여넣기로 여러 줄을 받기 때문이다 —
 * 한 명씩 보내면 같은 팀·권한 프리셋을 매번 다시 고르게 된다.
 */
@Schema(description = "초대 생성 요청. 이메일 여러 개에 같은 팀·권한 프리셋을 한 번에 건다.")
public record InvitationCreateRequest(
        @Schema(description = "초대할 이메일 목록. 이미 활성이거나 정지된 계정의 이메일이면 409.",
                example = "[\"chanho@example.com\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "초대할 이메일을 입력하세요") List<String> emails,
        @Schema(description = "수락 시 넣을 팀 프리셋") List<TeamPreset> teams,
        @Schema(description = "수락 시 줄 권한 프리셋") List<GrantPreset> grants,
        @Schema(description = "초대 메일에 함께 실을 메시지. 500자까지.", example = "플랫폼팀에서 함께 일하게 됐습니다.")
        @Size(max = 500, message = "메시지는 500자까지 입력할 수 있습니다") String message) {

    @Schema(description = "팀 프리셋")
    public record TeamPreset(
            @Schema(description = "팀 id", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Long teamId,
            @Schema(description = "팀 내 역할. 비우면 MEMBER.", example = "MEMBER") TeamRole role) {}

    /** GLOBAL이면 resourceId는 비워 둔다(grant_entry와 같은 표현). */
    @Schema(description = "권한 프리셋. GLOBAL이면 resourceId를 비워 둔다.")
    public record GrantPreset(
            @Schema(description = "권한 범위", example = "SPACE", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull ResourceKind scope,
            @Schema(description = "리소스 식별자. GLOBAL이면 비워 둔다.", example = "sp-1") String resourceId,
            @Schema(description = "역할", example = "EDITOR", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull GrantRole role) {}

    public List<TeamPreset> teamsOrEmpty() { return teams == null ? List.of() : teams; }

    public List<GrantPreset> grantsOrEmpty() { return grants == null ? List.of() : grants; }
}
