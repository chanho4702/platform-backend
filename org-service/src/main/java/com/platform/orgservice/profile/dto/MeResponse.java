package com.platform.orgservice.profile.dto;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.profile.MemberProfile;

import java.time.Instant;
import java.util.List;

/**
 * 내 프로필 — 화면 우상단 사용자 메뉴가 이름·아바타를 여기서 받는다.
 *
 * <p>U1에서 {@code status}·{@code kind}·{@code joinedVia}·{@code globalRoles}·{@code teams}가 붙었다.
 * 프론트의 전역 관리자 판정을 여기로 통일하기 위해서다 — 지금까지는 관리자 전용 엔드포인트를
 * 찔러 보고 403이 아니면 관리자로 쳤는데, 그러면 판정 기준이 화면마다 갈라진다.
 * 승인 대기 계정도 이 응답만은 읽을 수 있다(그래야 "승인 대기" 화면을 그린다).
 */
public record MeResponse(Long id, String displayName, String email, String avatarUrl, Instant avatarUpdatedAt,
                         String status, String kind, String joinedVia,
                         List<String> globalRoles, List<TeamMembership> teams) {

    public record TeamMembership(Long id, String name, String kind, String role) {}

    public static MeResponse from(Member member, MemberProfile profile,
                                  List<String> globalRoles, List<TeamMembership> teams) {
        return new MeResponse(member.getId(), member.getDisplayName(), member.getEmail(),
                profile == null ? null : profile.avatarUrl(),
                profile == null ? null : profile.getAvatarUpdatedAt(),
                member.getStatus().name(), member.getKind().name(), member.getJoinedVia().name(),
                globalRoles, teams);
    }
}
