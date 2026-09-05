package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.Member;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 사용자 상세.
 *
 * <p>{@code grants}는 전역 관리자와 본인에게만 채워지고 나머지에게는 null이다 —
 * 누가 어느 팀인지는 조직도지만, 누가 어느 스페이스의 관리자인지는 그렇지 않다.
 */
@Schema(description = "멤버 상세")
public record MemberDetailResponse(
        @Schema(description = "멤버 id (= auth-server user id)", example = "42") Long id,
        @Schema(description = "표시 이름", example = "김찬호") String displayName,
        @Schema(description = "이메일", example = "chanho@example.com") String email,
        @Schema(description = "계정 상태", example = "ACTIVE",
                allowableValues = {"PENDING", "ACTIVE", "SUSPENDED", "DEACTIVATED"}) String status,
        @Schema(description = "멤버 종류", example = "HUMAN", allowableValues = {"HUMAN", "AGENT"}) String kind,
        @Schema(description = "합류 경로 — INVITE(초대 링크) | APPROVAL(관리자 승인) | BOOTSTRAP(최초 관리자 시드) | LEGACY(초대 제도 이전 계정)",
                example = "INVITE", allowableValues = {"INVITE", "APPROVAL", "BOOTSTRAP", "LEGACY"}) String joinedVia,
        @Schema(description = "승인한 관리자의 멤버 id. 승인 절차를 거치지 않았으면 null.", example = "1") Long approvedBy,
        @Schema(description = "승인 시각") Instant approvedAt,
        @Schema(description = "정지 시각. 정지된 적 없으면 null.") Instant suspendedAt,
        @Schema(description = "비활성 시각. 비활성된 적 없으면 null.") Instant deactivatedAt,
        @Schema(description = "생성 시각") Instant createdAt,
        @Schema(description = "마지막 변경 시각") Instant updatedAt,
        @Schema(description = "소속 팀 목록") List<TeamMembershipView> teams,
        @Schema(description = "권한 목록. 전역 관리자와 본인에게만 채워지고, 나머지에게는 null.")
        List<GrantView> grants) {

    @Schema(description = "소속 팀 한 줄")
    public record TeamMembershipView(
            @Schema(description = "팀 id", example = "3") Long teamId,
            @Schema(description = "팀 이름", example = "플랫폼팀") String name,
            @Schema(description = "팀 종류 — EVERYONE은 활성 사람 멤버 전원이 자동으로 속하는 팀이다",
                    example = "STANDARD", allowableValues = {"STANDARD", "EVERYONE"}) String kind,
            @Schema(description = "팀 내 역할", example = "MEMBER", allowableValues = {"LEAD", "MEMBER"}) String role) {}

    @Schema(description = "권한 한 줄")
    public record GrantView(
            @Schema(description = "권한 행 id", example = "17") Long id,
            @Schema(description = "리소스 종류", example = "SPACE") String resourceType,
            @Schema(description = "리소스 식별자. GLOBAL이면 null.", example = "sp-1") String resourceId,
            @Schema(description = "역할", example = "EDITOR",
                    allowableValues = {"VIEWER", "COMMENTER", "EDITOR", "ADMIN"}) String role) {}

    public static MemberDetailResponse of(Member m, List<TeamMembershipView> teams, List<GrantView> grants) {
        return new MemberDetailResponse(m.getId(), m.getDisplayName(), m.getEmail(),
                m.getStatus().name(), m.getKind().name(), m.getJoinedVia().name(),
                m.getApprovedBy(), m.getApprovedAt(), m.getSuspendedAt(), m.getDeactivatedAt(),
                m.getCreatedAt(), m.getUpdatedAt(), teams, grants);
    }
}
