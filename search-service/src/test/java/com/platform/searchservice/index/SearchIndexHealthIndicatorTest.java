package com.platform.searchservice.index;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/** 준비되지 않은 인스턴스가 UP으로 보고되면 대시보드·컨테이너 헬스체크가 거짓말을 한다. */
class SearchIndexHealthIndicatorTest {

    @Test
    void 색인_준비_전에는_DOWN과_실패_사유를_낸다() {
        SearchIndexReadiness readiness = new SearchIndexReadiness(true);
        readiness.recordFailure("IOException: connection refused");

        Health health = new SearchIndexHealthIndicator(readiness).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("index", "검색 색인 준비 중")
                .containsEntry("lastFailure", "IOException: connection refused");
    }

    @Test
    void 색인이_준비되면_UP이다() {
        SearchIndexReadiness readiness = new SearchIndexReadiness(true);
        readiness.markReady();

        Health health = new SearchIndexHealthIndicator(readiness).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("index", "ready");
    }
}
