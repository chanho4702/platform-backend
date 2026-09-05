package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.MemberEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 초대·상태 이력 한 줄. 권한 이력({@code /grants/audit})과 화면에서 나란히 놓인다. */
@Schema(description = "초대·상태 이력 한 줄. 권한 변경 이력은 /api/org/grants/audit이 따로 낸다.")
public record MemberEventResponse(
        @Schema(description = "이력 id", example = "108") Long id,
        @Schema(description = "대상 멤버 id. 아직 멤버가 아닌 초대 단계면 null.", example = "42") Long memberId,
        @Schema(description = "관련 초대 id. 초대와 무관한 이력이면 null.", example = "7") Long invitationId,
        @Schema(description = "이력 종류 — INVITED · INVITE_RESENT · INVITE_REVOKED · INVITE_EXPIRED · JOINED · APPROVED · SUSPENDED · REACTIVATED · DEACTIVATED · TEAM_ADDED · TEAM_REMOVED · KEYCLOAK_DISABLED_FAILED",
                example = "APPROVED") String type,
        @Schema(description = "이 일을 한 사람의 멤버 id. 시스템이 한 일이면 null.", example = "1") Long actorId,
        @Schema(description = "부가 설명. 종류마다 담기는 값이 다르다.", example = "team=3") String detail,
        @Schema(description = "발생 시각") Instant createdAt) {

    public static MemberEventResponse from(MemberEvent e) {
        return new MemberEventResponse(e.getId(), e.getMemberId(), e.getInvitationId(),
                e.getType().name(), e.getActorId(), e.getDetail(), e.getCreatedAt());
    }
}
