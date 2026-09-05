package com.platform.orgservice.profile.dto;

import com.platform.orgservice.profile.MemberProfile;

import java.time.Instant;

/** 아바타 업로드 응답 — 프론트는 avatarUrl을 fetch 대상 경로이자 "아바타가 있다"는 신호로 쓴다 */
public record AvatarView(long memberId, String avatarUrl, Instant updatedAt) {
    public static AvatarView from(MemberProfile profile) {
        return new AvatarView(profile.getMemberId(), profile.avatarUrl(), profile.getAvatarUpdatedAt());
    }
}
