package com.platform.searchservice.index;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 최초 물리 인덱스와 코드가 사용하는 별칭을 기동 시 확보한다.
 *
 * <p><b>실패해도 기동을 막지 않는다.</b> 설치 옵션 {@code SEARCH_MODE=external}에서는 고객사
 * OpenSearch/ES 클러스터가 우리보다 늦게 뜨는 것이 정상 경로다. 예전처럼 기동을 중단하면
 * 컨테이너가 재시작 루프에 빠지고, 배포는 "클러스터가 30초 늦었다"를 장애로 오진단한다.
 *
 * <p>대신 준비될 때까지 백오프(5s → 10s → … → 60s 상한)로 <b>무한 재시도</b>하고, 그동안은
 * {@link SearchIndexReadiness}가 게이트를 닫아 둔다: 검색 요청은 503("검색 색인 준비 중"),
 * 이벤트 소비는 시작하지 않는다. 소비를 막는 이유가 특히 중요하다 — 색인이 없는 채로 소비하면
 * 처리 실패가 재시도 상한을 넘겨 원본 이벤트를 DLQ로 흘려보낸다(08-02 무음 유실의 재현).
 */
@Component
@ConditionalOnProperty(value = "platform.opensearch.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OpenSearchIndexBootstrap implements ApplicationRunner {

    static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(5);
    static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(60);
    /** 이 횟수마다 한 번은 ERROR로 올린다 — 장시간 준비되지 않는 설치가 WARN에 묻히지 않게. */
    private static final int ESCALATE_EVERY = 10;

    private final OpenSearchIndexFactory factory;
    private final SearchIndexReadiness readiness;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final AtomicBoolean retrying = new AtomicBoolean();
    private volatile Thread retryThread;

    @Autowired
    public OpenSearchIndexBootstrap(OpenSearchIndexFactory factory, SearchIndexReadiness readiness) {
        this(factory, readiness, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF);
    }

    /** 테스트 전용 — 백오프를 줄여 주입한다. 스프링은 이 생성자를 쓰지 않는다. */
    OpenSearchIndexBootstrap(
            OpenSearchIndexFactory factory,
            SearchIndexReadiness readiness,
            Duration initialBackoff,
            Duration maxBackoff) {
        this.factory = factory;
        this.readiness = readiness;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    /**
     * 첫 시도는 이 스레드에서 동기로 한다 — 정상 배포(OpenSearch가 이미 떠 있는 경우)의 동작을
     * 예전과 같게 유지해, 기동 직후 첫 검색이 경합으로 503을 받는 일이 없게 한다.
     */
    @Override
    public void run(ApplicationArguments args) {
        readiness.markPending("색인 준비 시도 전");
        if (attempt(1)) {
            readiness.markReady();
            return;
        }
        startRetryLoop();
    }

    /** 통합 테스트와 재기동 복구가 같은 경로를 직접 실행할 수 있게 공개한다. */
    public void initialize() throws IOException {
        if (!factory.client().ping().value()) {
            throw new IOException("OpenSearch ping 응답이 정상이 아닙니다");
        }
        ensureIndex(IndexNames.PAGE_INDEX_V1, IndexNames.PAGE_ALIAS, OpenSearchIndexFactory.PAGE_MAPPING);
        ensureIndex(IndexNames.ATTACHMENT_INDEX_V1, IndexNames.ATTACHMENT_ALIAS,
                OpenSearchIndexFactory.ATTACHMENT_MAPPING);
        log.info("OpenSearch 색인 준비 완료: aliases=[{}, {}]",
                IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS);
    }

    /** 다음 대기 시간 — 두 배씩 늘리고 상한에서 멈춘다. */
    static Duration nextBackoff(Duration current, Duration max) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(max) > 0 ? max : doubled;
    }

    private boolean attempt(long attemptNo) {
        try {
            initialize();
            return true;
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            readiness.recordFailure(reason);
            if (attemptNo % ESCALATE_EVERY == 0) {
                log.error("OpenSearch 색인이 {}회 시도 동안 준비되지 않았습니다 — 검색은 503, 색인 갱신 중단 상태입니다. "
                        + "OPENSEARCH_URI와 클러스터 상태를 확인하세요.", attemptNo, e);
            } else {
                log.warn("OpenSearch 색인 준비 실패(attempt={}) — 기동은 계속하고 재시도합니다. "
                        + "그동안 검색 요청은 503, 이벤트 소비는 대기합니다.", attemptNo, e);
            }
            return false;
        }
    }

    private void startRetryLoop() {
        if (!retrying.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::retryLoop, "opensearch-bootstrap-retry");
        thread.setDaemon(true);
        retryThread = thread;
        thread.start();
    }

    private void retryLoop() {
        Duration backoff = initialBackoff;
        long attemptNo = 1;
        while (!Thread.currentThread().isInterrupted()) {
            if (!sleep(backoff)) {
                return;
            }
            attemptNo++;
            if (attempt(attemptNo)) {
                readiness.markReady();
                return;
            }
            backoff = nextBackoff(backoff, maxBackoff);
        }
    }

    /** @return 계속 진행할지 여부(인터럽트되면 false). */
    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    void stop() {
        Thread thread = retryThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void ensureIndex(String physicalIndex, String alias, String mappingResource) throws IOException {
        if (!factory.indexExists(physicalIndex)) {
            factory.createIndex(physicalIndex, mappingResource);
        }

        // 재색인 중 별칭이 이미 v2를 가리킬 수 있으므로, 존재하는 별칭을 v1로 되돌리지 않는다.
        if (!factory.aliasExists(alias)) {
            factory.client().indices().putAlias(p -> p
                    .index(physicalIndex)
                    .name(alias)
                    .isWriteIndex(true));
            log.info("OpenSearch 별칭 생성: alias={} index={}", alias, physicalIndex);
        }
    }
}
