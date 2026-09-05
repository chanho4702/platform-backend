package com.platform.orgservice.profile.dto;

import com.platform.orgservice.profile.MemberProfile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 아바타 업로드 응답 — 프론트는 avatarUrl을 fetch 대상 경로이자 "아바타가 있다"는 신호로 쓴다 */
@Schema(description = "아바타 업로드 결과")
public record AvatarView(
        @Schema(description = "멤버 id", example = "42") long memberId,
        @Schema(description = "아바타 이미지 경로. 프론트는 이 값의 유무를 '아바타가 있다'는 신호로도 쓴다.",
                example = "/api/org/members/42/avatar?v=1757030400000") String avatarUrl,
        @Schema(description = "이 아바타를 올린 시각") Instant updatedAt) {
    public static AvatarView from(MemberProfile profile) {
        return new AvatarView(profile.getMemberId(), profile.avatarUrl(), profile.getAvatarUpdatedAt());
    }
}
