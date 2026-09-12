package com.platform.searchservice.index;

import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 기동 복원력 — OpenSearch가 늦게 뜨는 설치({@code SEARCH_MODE=external})에서 프로세스가
 * 죽지 않고 스스로 회복하는지.
 *
 * <p>실 클러스터가 필요 없도록 {@code initialize()}만 대역으로 바꾼다. 색인·별칭 생성 자체는
 * Testcontainers 테스트가 실제 OpenSearch에 대고 따로 검증한다.
 */
class OpenSearchIndexBootstrapRetryTest {

    private static final Duration FAST_INITIAL = Duration.ofMillis(5);
    private static final Duration FAST_MAX = Duration.ofMillis(20);

    @Test
    void OpenSearch가_없어도_기동을_중단하지_않는다() {
        StubBootstrap bootstrap = new StubBootstrap(Integer.MAX_VALUE);

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();

        assertThat(bootstrap.readiness.isReady()).isFalse();
        assertThat(bootstrap.readiness.lastFailure()).contains("connection refused");
        bootstrap.stop();
    }

    @Test
    void 준비되기_전에는_검색_요청을_받지_않고_이벤트_소비도_시작하지_않는다() {
        StubBootstrap bootstrap = new StubBootstrap(Integer.MAX_VALUE);
        List<String> consumerStarts = new ArrayList<>();
        bootstrap.readiness.whenReady(() -> consumerStarts.add("소비 시작"));

        bootstrap.run(null);

        assertThatThrownBy(bootstrap.readiness::requireReady)
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("검색 색인 준비 중");
        assertThat(consumerStarts).isEmpty();
        bootstrap.stop();
    }

    @Test
    void 늦게_뜬_클러스터에_재시도로_붙으면_준비가_열리고_소비가_시작된다() {
        StubBootstrap bootstrap = new StubBootstrap(2);   // 첫 두 번 실패 후 성공
        List<String> consumerStarts = new ArrayList<>();
        bootstrap.readiness.whenReady(() -> consumerStarts.add("소비 시작"));

        bootstrap.run(null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(bootstrap.readiness.isReady()).isTrue();
            assertThat(consumerStarts).containsExactly("소비 시작");
        });
        assertThat(bootstrap.attempts.get()).isEqualTo(3);
        assertThat(bootstrap.readiness.lastFailure()).isNull();
        bootstrap.stop();
    }

    @Test
    void 클러스터가_이미_떠_있으면_첫_시도에서_동기로_준비된다() {
        StubBootstrap bootstrap = new StubBootstrap(0);

        bootstrap.run(null);

        // run()이 돌아온 시점에 이미 열려 있어야 한다 — 기동 직후 첫 검색이 경합으로 503을 받지 않게.
        assertThat(bootstrap.readiness.isReady()).isTrue();
        assertThat(bootstrap.attempts.get()).isEqualTo(1);
        bootstrap.stop();
    }

    @Test
    void 백오프는_두_배씩_늘고_상한에서_멈춘다() {
        Duration max = OpenSearchIndexBootstrap.DEFAULT_MAX_BACKOFF;
        List<Duration> schedule = new ArrayList<>();
        Duration backoff = OpenSearchIndexBootstrap.DEFAULT_INITIAL_BACKOFF;
        for (int i = 0; i < 6; i++) {
            schedule.add(backoff);
            backoff = OpenSearchIndexBootstrap.nextBackoff(backoff, max);
        }

        assertThat(schedule).containsExactly(
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                Duration.ofSeconds(40),
                Duration.ofSeconds(60),
                Duration.ofSeconds(60));
    }

    /** {@code initialize()}가 지정한 횟수만큼 실패한 뒤 성공하는 대역. */
    private static final class StubBootstrap extends OpenSearchIndexBootstrap {

        private final SearchIndexReadiness readiness;
        private final AtomicInteger attempts = new AtomicInteger();
        private final int failuresBeforeSuccess;

        private StubBootstrap(int failuresBeforeSuccess) {
            this(new SearchIndexReadiness(true), failuresBeforeSuccess);
        }

        private StubBootstrap(SearchIndexReadiness readiness, int failuresBeforeSuccess) {
            super(null, readiness, FAST_INITIAL, FAST_MAX);
            this.readiness = readiness;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public void initialize() throws IOException {
            if (attempts.incrementAndGet() <= failuresBeforeSuccess) {
                throw new IOException("connection refused");
            }
        }
    }
}
