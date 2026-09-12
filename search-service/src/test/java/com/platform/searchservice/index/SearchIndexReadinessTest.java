package com.platform.searchservice.index;

import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 준비 게이트의 계약 — 검색 503, 소비 지연 시작, 부트스트랩이 없는 구성에서는 게이트 없음. */
class SearchIndexReadinessTest {

    @Test
    void 부트스트랩이_켜져_있으면_준비되기_전까지_검색을_503으로_거절한다() {
        SearchIndexReadiness readiness = new SearchIndexReadiness(true);

        assertThat(readiness.isReady()).isFalse();
        assertThatThrownBy(readiness::requireReady)
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("검색 색인 준비 중");
    }

    /**
     * 부트스트랩이 색인을 확보하지 않는 구성에서 게이트를 닫아 두면 아무도 열지 않는다 —
     * 모든 검색이 영구 503으로 굳는 회귀를 여기서 고정한다.
     */
    @Test
    void 부트스트랩이_꺼진_구성은_처음부터_열려_있다() {
        SearchIndexReadiness readiness = new SearchIndexReadiness(false);

        assertThat(readiness.isReady()).isTrue();
        assertThatCode(readiness::requireReady).doesNotThrowAnyException();
    }

    @Test
    void 준비_전에_건_작업은_준비된_뒤에_실행된다() {
        SearchIndexReadiness readiness = new SearchIndexReadiness(true);
        List<String> executed = new ArrayList<>();

        readiness.whenReady(() -> executed.add("소비 시작"));
        assertThat(executed).isEmpty();

        readiness.markReady();

        assertThat(executed).containsExactly("소비 시작");
        assertThat(readiness.isReady()).isTrue();
        assertThatCode(readiness::requireReady).doesNotThrowAnyException();
    }

    @Test
    void 이미_준비됐으면_건_작업을_즉시_실행한다() {
        SearchIndexReadiness readiness = new SearchIndexReadiness(true);
        readiness.markReady();
        List<String> executed = new ArrayList<>();

        readiness.whenReady(() -> executed.add("소비 시작"));

        assertThat(executed).containsExactly("소비 시작");
    }

    @Test
    void 대기_작업은_한_번만_실행된다() {
        SearchIndexReadiness readiness = new SearchIndexReadiness(true);
        List<String> executed = new ArrayList<>();
        readiness.whenReady(() -> executed.add("소비 시작"));

        readiness.markReady();
        readiness.markReady();

        assertThat(executed).containsExactly("소비 시작");
    }

    @Test
    void 실패_사유는_준비되면_지워진다() {
        SearchIndexReadiness readiness = new SearchIndexReadiness(true);

        readiness.markPending("색인 준비 시도 전");
        readiness.recordFailure("IOException: connection refused");
        assertThat(readiness.lastFailure()).isEqualTo("IOException: connection refused");

        readiness.markReady();
        assertThat(readiness.lastFailure()).isNull();
    }
}
