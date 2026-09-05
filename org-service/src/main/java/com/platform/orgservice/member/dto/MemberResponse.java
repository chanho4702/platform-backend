package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.profile.MemberProfile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 멤버 목록 항목. {@code avatarUrl}·{@code avatarUpdatedAt}은 아바타를 올린 멤버만 채워지고
 * 나머지는 null이다 — 기존 필드의 의미는 그대로다.
 */
@Schema(description = "멤버 목록 항목")
public record MemberResponse(
        @Schema(description = "멤버 id (= auth-server user id)", example = "42") Long id,
        @Schema(description = "표시 이름. 원천은 Keycloak 프로필이다.", example = "김찬호") String displayName,
        @Schema(description = "이메일", example = "chanho@example.com") String email,
        @Schema(description = "계정 상태", example = "ACTIVE",
                allowableValues = {"PENDING", "ACTIVE", "SUSPENDED", "DEACTIVATED"}) String status,
        @Schema(description = "멤버 종류 — 사람인지 에이전트 페르소나인지", example = "HUMAN",
                allowableValues = {"HUMAN", "AGENT"}) String kind,
        @Schema(description = "아바타 이미지 경로. 아바타를 올리지 않았으면 null.",
                example = "/api/org/members/42/avatar?v=1757030400000") String avatarUrl,
        @Schema(description = "아바타를 마지막으로 바꾼 시각. 없으면 null.") Instant avatarUpdatedAt) {

    /** 아바타를 싣지 않는 응답(에이전트 등록 등) */
    public static MemberResponse from(Member m) {
        return from(m, null);
    }

    public static MemberResponse from(Member m, MemberProfile profile) {
        return new MemberResponse(m.getId(), m.getDisplayName(), m.getEmail(), m.getStatus().name(),
                m.getKind().name(),
                profile == null ? null : profile.avatarUrl(),
                profile == null ? null : profile.getAvatarUpdatedAt());
    }
}
