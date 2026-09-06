package com.platform.orgservice.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 조직 현황 한 장(관리자 대시보드 §4.2).
 *
 * <p>{@code members}는 <b>사람 멤버</b>만 상태별로 센다 — 에이전트 페르소나는 {@code agents}로 따로
 * 나가므로, 두 값을 더하면 전체 멤버가 된다(어느 쪽에도 이중으로 세지 않는다).
 * 네 상태는 값이 0이어도 항상 들어간다 — 화면이 키 존재 여부를 확인하지 않아도 되게.
 */
@Schema(description = "조직 현황 통계 — 멤버 상태별 수, 에이전트, 팀, 대기 중 초대")
public record OrgStatsResponse(
        @Schema(description = "사람 멤버의 상태별 수. 키는 ACTIVE·PENDING·SUSPENDED·DEACTIVATED 넷이며 0도 포함한다",
                example = "{\"ACTIVE\":31,\"PENDING\":2,\"SUSPENDED\":1,\"DEACTIVATED\":4}")
        Map<String, Long> members,
        @Schema(description = "에이전트 페르소나 멤버 수(로그인 없는 AI 계정)", example = "3")
        long agents,
        @Schema(description = "팀 수 — \"전체 구성원\" 팀을 포함한다", example = "6")
        long teams,
        @Schema(description = "아직 수락되지 않은 초대 수(PENDING)", example = "2")
        long pendingInvitations) {
}
