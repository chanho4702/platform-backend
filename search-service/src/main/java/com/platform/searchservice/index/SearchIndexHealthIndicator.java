package com.platform.searchservice.index;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 색인 준비 상태를 {@code /actuator/health}에 드러낸다({@code searchIndex} 항목).
 *
 * <p>OpenSearch가 아직 없어 재시도 중인 인스턴스는 <b>DOWN</b>이다 — 프로세스는 살아 있지만
 * 검색을 수행하지 못하고 이벤트도 소비하지 않으므로, 살아있음만으로 UP을 내면 관리자 대시보드와
 * 컨테이너 헬스체크가 "정상"이라고 거짓말하게 된다. 기동을 중단하지 않는 대신 이 신호를 낸다.
 */
@Component
@RequiredArgsConstructor
public class SearchIndexHealthIndicator implements HealthIndicator {

    private final SearchIndexReadiness readiness;

    @Override
    public Health health() {
        if (readiness.isReady()) {
            return Health.up().withDetail("index", "ready").build();
        }
        Health.Builder down = Health.down().withDetail("index", SearchIndexReadiness.NOT_READY_MESSAGE);
        String failure = readiness.lastFailure();
        if (failure != null) {
            down.withDetail("lastFailure", failure);
        }
        return down.build();
    }
}
