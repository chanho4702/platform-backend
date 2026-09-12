package com.platform.searchservice.reindex;

import com.platform.common.error.ForbiddenException;
import com.platform.searchservice.index.SearchIndexReadiness;
import com.platform.searchservice.permission.PermissionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재색인 관리 REST (설계 §9).
 *
 * GraphQL이 아니라 REST인 이유는 운영 조작이기 때문이다 — 장애 대응 중에 curl로 때릴 수 있어야 한다.
 *
 * 경로는 서비스 내부 기준이다. 게이트웨이가 {@code Path=/api/search/**} + {@code StripPrefix=2}로
 * 접두사를 떼고 넘기므로 외부 노출 경로는 {@code /api/search/admin/reindex}가 된다.
 */
@RestController
@RequestMapping("/admin/reindex")
@RequiredArgsConstructor
@Slf4j
public class ReindexAdminController {

    private final ReindexService reindex;
    private final PermissionClient permissions;
    private final SearchIndexReadiness readiness;

    @PostMapping
    public ResponseEntity<ReindexJobView> start(@AuthenticationPrincipal Jwt jwt) {
        long userId = requireGlobalAdmin(jwt);
        // 색인 준비 전 재색인은 시작하자마자 실패한다. 원인이 드러나는 503으로 먼저 막는다.
        readiness.requireReady();
        ReindexJobView job = reindex.start();
        // 색인 전체를 다시 만드는 조작이라 누가 언제 눌렀는지가 사후 추적의 출발점이다.
        log.info("재색인 요청 수락: jobId={} requestedBy={}", job.jobId(), userId);
        return ResponseEntity.accepted().body(job);
    }

    /**
     * 색인 현황. 관리 화면이 뜰 때 부르고, **전역 관리자 여부를 확인하는 창구**이기도 하다 —
     * 아니면 403이 오고, 화면은 관리 메뉴 자체를 감춘다.
     */
    @GetMapping("/status")
    public ReindexStatusView indexStatus(@AuthenticationPrincipal Jwt jwt) {
        requireGlobalAdmin(jwt);
        readiness.requireReady();
        return reindex.indexStatus();
    }

    @GetMapping("/{jobId}")
    public ReindexJobView status(@PathVariable String jobId, @AuthenticationPrincipal Jwt jwt) {
        requireGlobalAdmin(jwt);
        return reindex.status(jobId);
    }

    /**
     * 인증(Security)만으로는 부족하다 — 재색인은 전역 관리자 조작이다(설계 §9).
     *
     * 판정 불능은 여기서 403으로 바뀌지 않는다: {@link PermissionClient}가 던지는
     * ServiceUnavailableException이 그대로 503으로 나간다.
     */
    private long requireGlobalAdmin(Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        if (!permissions.isGlobalAdmin(userId)) {
            log.warn("전역 관리자가 아닌 사용자의 재색인 요청 거부: user={}", userId);
            throw new ForbiddenException("재색인은 전역 관리자만 실행할 수 있습니다");
        }
        return userId;
    }
}
