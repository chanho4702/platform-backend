package com.platform.orgservice.invitation.dto;

import com.platform.orgservice.domain.Invitation;

import java.time.Instant;
import java.util.List;

/**
 * 초대 한 건.
 *
 * <p>{@code inviteUrl}은 <b>방금 만들었거나 방금 재발송한 응답에만</b> 담긴다. 토큰 원문을 저장하지
 * 않으므로 목록에서는 되살릴 수 없다(항상 null) — 링크가 다시 필요하면 재발송으로 새 토큰을 받는다.
 * {@code mailSent}가 false면 화면이 링크 복사를 안내한다.
 */
public record InvitationResponse(
        Long id,
        String email,
        String status,
        Long invitedBy,
        String invitedByName,
        String message,
        Instant expiresAt,
        Instant createdAt,
        Long acceptedMemberId,
        Instant acceptedAt,
        String acceptedVia,
        List<TeamPresetView> teams,
        List<GrantPresetView> grants,
        String inviteUrl,
        Boolean mailSent) {

    public record TeamPresetView(Long teamId, String name, String role) {}

    public record GrantPresetView(String scope, String resourceId, String role) {}

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
