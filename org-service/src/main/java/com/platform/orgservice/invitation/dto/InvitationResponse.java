package com.platform.orgservice.invitation.dto;

import com.platform.orgservice.domain.Invitation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 초대 한 건.
 *
 * <p>{@code inviteUrl}은 <b>방금 만들었거나 방금 재발송한 응답에만</b> 담긴다. 토큰 원문을 저장하지
 * 않으므로 목록에서는 되살릴 수 없다(항상 null) — 링크가 다시 필요하면 재발송으로 새 토큰을 받는다.
 * {@code mailSent}가 false면 화면이 링크 복사를 안내한다.
 */
@Schema(description = "초대 한 건")
public record InvitationResponse(
        @Schema(description = "초대 id", example = "7") Long id,
        @Schema(description = "초대받은 이메일", example = "chanho@example.com") String email,
        @Schema(description = "초대 상태. PENDING만 살아 있는 초대다.", example = "PENDING",
                allowableValues = {"PENDING", "ACCEPTED", "EXPIRED", "REVOKED"}) String status,
        @Schema(description = "초대한 사람의 멤버 id", example = "1") Long invitedBy,
        @Schema(description = "초대한 사람의 표시 이름", example = "김찬호") String invitedByName,
        @Schema(description = "초대 메일에 실은 메시지", example = "플랫폼팀에서 함께 일하게 됐습니다.") String message,
        @Schema(description = "만료 시각. 기본 유효기간은 7일이다.") Instant expiresAt,
        @Schema(description = "생성 시각") Instant createdAt,
        @Schema(description = "수락한 멤버 id. 아직 수락 전이면 null.", example = "42") Long acceptedMemberId,
        @Schema(description = "수락 시각. 아직 수락 전이면 null.") Instant acceptedAt,
        @Schema(description = "수락 경로 — TOKEN(초대 링크 경유) | EMAIL_MATCH(이메일 일치). 수락 전이면 null.",
                example = "TOKEN", allowableValues = {"TOKEN", "EMAIL_MATCH"}) String acceptedVia,
        @Schema(description = "수락 시 적용될 팀 프리셋") List<TeamPresetView> teams,
        @Schema(description = "수락 시 적용될 권한 프리셋") List<GrantPresetView> grants,
        @Schema(description = "초대 링크. 생성·재발송 응답에만 담기고 목록에서는 항상 null이다(토큰 원문을 저장하지 않는다).",
                example = "https://platform.example.com/invite/AbCd…") String inviteUrl,
        @Schema(description = "메일을 실제로 보냈는지. false면 화면이 링크 복사를 안내한다.", example = "true")
        Boolean mailSent) {

    @Schema(description = "팀 프리셋")
    public record TeamPresetView(
            @Schema(description = "팀 id", example = "3") Long teamId,
            @Schema(description = "팀 이름", example = "플랫폼팀") String name,
            @Schema(description = "팀 내 역할", example = "MEMBER", allowableValues = {"LEAD", "MEMBER"}) String role) {}

    @Schema(description = "권한 프리셋")
    public record GrantPresetView(
            @Schema(description = "권한 범위", example = "SPACE",
                    allowableValues = {"GLOBAL", "SPACE", "PROJECT"}) String scope,
            @Schema(description = "리소스 식별자. GLOBAL이면 null.", example = "sp-1") String resourceId,
            @Schema(description = "역할", example = "EDITOR",
                    allowableValues = {"VIEWER", "COMMENTER", "EDITOR", "ADMIN"}) String role) {}

    public static InvitationResponse of(Invitation i, String invitedByName,
                                        List<TeamPresetView> teams, List<GrantPresetView> grants,
                                        String inviteUrl, Boolean mailSent) {
        return new InvitationResponse(
                i.getId(), i.getEmail(), i.getStatus().name(), i.getInvitedBy(), invitedByName,
                i.getMessage(), i.getExpiresAt(), i.getCreatedAt(),
                i.getAcceptedMemberId(), i.getAcceptedAt(),
                i.getAcceptedVia() == null ? null : i.getAcceptedVia().name(),
                teams, grants, inviteUrl, mailSent);
    }
}
