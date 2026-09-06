package com.platform.orgservice.admin;

import com.platform.orgservice.admin.dto.OrgStatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "관리자 대시보드가 읽는 조직 현황 — 전역 관리자 전용")
@RestController
@RequestMapping("/api/org/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final OrgStatsService stats;

    @Operation(summary = "조직 현황 통계 조회 — 사람 멤버의 상태별 수·에이전트·팀·대기 중 초대. 서버에서 60초 캐시한다")
    @GetMapping("/stats")
    public OrgStatsResponse stats(@AuthenticationPrincipal Jwt jwt) {
        return stats.stats(userId(jwt));
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
