package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.Member;

import java.time.Instant;
import java.util.List;

/**
 * 사용자 상세.
 *
 * <p>{@code grants}는 전역 관리자와 본인에게만 채워지고 나머지에게는 null이다 —
 * 누가 어느 팀인지는 조직도지만, 누가 어느 스페이스의 관리자인지는 그렇지 않다.
 */
public record MemberDetailResponse(
        Long id, String displayName, String email, String status, String kind, String joinedVia,
        Long approvedBy, Instant approvedAt, Instant suspendedAt, Instant deactivatedAt,
        Instant createdAt, Instant updatedAt,
        List<TeamMembershipView> teams,
        List<GrantView> grants) {

    public record TeamMembershipView(Long teamId, String name, String kind, String role) {}

    public record GrantView(Long id, String resourceType, String resourceId, String role) {}

    public static MemberDetailResponse of(Member m, List<TeamMembershipView> teams, List<GrantView> grants) {
        return new MemberDetailResponse(m.getId(), m.getDisplayName(), m.getEmail(),
                m.getStatus().name(), m.getKind().name(), m.getJoinedVia().name(),
                m.getApprovedBy(), m.getApprovedAt(), m.getSuspendedAt(), m.getDeactivatedAt(),
                m.getCreatedAt(), m.getUpdatedAt(), teams, grants);
    }
}
