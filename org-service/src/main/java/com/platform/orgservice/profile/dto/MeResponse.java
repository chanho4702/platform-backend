package com.platform.orgservice.profile.dto;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.profile.MemberProfile;

import java.time.Instant;

/** 내 프로필 — 화면 우상단 사용자 메뉴가 이름·아바타를 여기서 받는다 */
public record MeResponse(Long id, String displayName, String email, String avatarUrl, Instant avatarUpdatedAt) {
    public static MeResponse from(Member member, MemberProfile profile) {
        return new MeResponse(member.getId(), member.getDisplayName(), member.getEmail(),
                profile == null ? null : profile.avatarUrl(),
                profile == null ? null : profile.getAvatarUpdatedAt());
    }
}
