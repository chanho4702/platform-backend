package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.profile.MemberProfile;

import java.time.Instant;

/**
 * 멤버 목록 항목. {@code avatarUrl}·{@code avatarUpdatedAt}은 아바타를 올린 멤버만 채워지고
 * 나머지는 null이다 — 기존 필드의 의미는 그대로다.
 */
public record MemberResponse(Long id, String displayName, String email, String status, String kind,
                             String avatarUrl, Instant avatarUpdatedAt) {

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
