package com.platform.orgservice.profile.dto;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.profile.MemberProfile;
import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(description = "내 프로필. 프론트의 전역 관리자 판정은 globalRoles에 ADMIN이 있는지로 통일한다.")
public record MeResponse(
        @Schema(description = "내 멤버 id (= auth-server user id)", example = "42") Long id,
        @Schema(description = "표시 이름", example = "김찬호") String displayName,
        @Schema(description = "이메일", example = "chanho@example.com") String email,
        @Schema(description = "아바타 이미지 경로. 없으면 null.",
                example = "/api/org/members/42/avatar?v=1757030400000") String avatarUrl,
        @Schema(description = "아바타를 마지막으로 바꾼 시각. 없으면 null.") Instant avatarUpdatedAt,
        @Schema(description = "계정 상태. ACTIVE가 아니면 이 경로 밖은 403이다.", example = "ACTIVE",
                allowableValues = {"PENDING", "ACTIVE", "SUSPENDED", "DEACTIVATED"}) String status,
        @Schema(description = "멤버 종류", example = "HUMAN", allowableValues = {"HUMAN", "AGENT"}) String kind,
        @Schema(description = "합류 경로", example = "INVITE",
                allowableValues = {"INVITE", "APPROVAL", "BOOTSTRAP", "LEGACY"}) String joinedVia,
        @Schema(description = "GLOBAL 권한의 역할 목록. 여기에 ADMIN이 있으면 전역 관리자다.",
                example = "[\"ADMIN\"]") List<String> globalRoles,
        @Schema(description = "소속 팀 목록") List<TeamMembership> teams) {

    @Schema(description = "소속 팀 한 줄")
    public record TeamMembership(
            @Schema(description = "팀 id", example = "3") Long id,
            @Schema(description = "팀 이름", example = "플랫폼팀") String name,
            @Schema(description = "팀 종류", example = "STANDARD",
                    allowableValues = {"STANDARD", "EVERYONE"}) String kind,
            @Schema(description = "팀 내 역할", example = "MEMBER",
                    allowableValues = {"LEAD", "MEMBER"}) String role) {}

    public static MeResponse from(Member member, MemberProfile profile,
                                  List<String> globalRoles, List<TeamMembership> teams) {
        return new MeResponse(member.getId(), member.getDisplayName(), member.getEmail(),
                profile == null ? null : profile.avatarUrl(),
                profile == null ? null : profile.getAvatarUpdatedAt(),
                member.getStatus().name(), member.getKind().name(), member.getJoinedVia().name(),
                globalRoles, teams);
    }
}
