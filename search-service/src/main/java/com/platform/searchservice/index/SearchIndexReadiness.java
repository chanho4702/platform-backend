package com.platform.searchservice.index;

import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 색인 준비 게이트.
 *
 * <p>기동 시 OpenSearch가 아직 없을 수 있다(설치 옵션 {@code SEARCH_MODE=external} — 고객사
 * 클러스터가 늦게 뜨는 경우). 그때 프로세스를 죽이는 대신 <b>준비되지 않았음을 이 한 곳에서
 * 표현</b>하고, 준비될 때까지
 *
 * <ul>
 *   <li>검색 요청은 {@link ServiceUnavailableException}("검색 색인 준비 중")로 거절하고,</li>
 *   <li>이벤트 소비는 아예 시작하지 않는다({@link #whenReady(Runnable)}).</li>
 * </ul>
 *
 * <p>소비를 막는 것이 핵심이다. 색인 없이 소비를 시작하면 처리 실패가 재시도 상한을 넘기며
 * 원본 이벤트가 DLQ로 흘러간다 — 08-02의 무음 유실과 같은 결과가 된다. 소비하지 않으면
 * 이벤트는 스트림에 남고, 컨슈머 그룹은 {@code 0-0}에서 만들어지므로 준비된 뒤 재생된다.
 */
@Component
@Slf4j
public class SearchIndexReadiness {

    /** 프론트에 그대로 보이는 한국어 메시지 — REST는 {@code {"error": ...}}, GraphQL은 오류 message. */
    public static final String NOT_READY_MESSAGE = "검색 색인 준비 중";

    private final AtomicBoolean ready = new AtomicBoolean();
    private final Queue<Runnable> waiting = new ConcurrentLinkedQueue<>();
    private volatile String lastFailure;

    public SearchIndexReadiness(
            @Value("${platform.opensearch.bootstrap.enabled:true}") boolean bootstrapEnabled) {
        // 부트스트랩이 색인을 확보하지 않는 구성(테스트·외부 운영 절차)에서는 게이트도 두지 않는다.
        // 두면 아무도 markReady()를 부르지 않아 모든 검색이 영구히 503으로 굳는다.
        ready.set(!bootstrapEnabled);
    }

    public boolean isReady() {
        return ready.get();
    }

    /** 마지막 준비 실패 사유. 준비 완료면 null. */
    public String lastFailure() {
        return lastFailure;
    }

    /** 검색·재색인 진입점의 가드. 준비 전이면 503 계약으로 거절한다. */
    public void requireReady() {
        if (!ready.get()) {
            throw new ServiceUnavailableException(NOT_READY_MESSAGE);
        }
    }

    void markPending(String reason) {
        lastFailure = reason;
        ready.set(false);
    }

    void recordFailure(String reason) {
        if (!ready.get()) {
            lastFailure = reason;
        }
    }

    void markReady() {
        lastFailure = null;
        if (ready.compareAndSet(false, true)) {
            log.info("검색 색인 준비 완료 — 검색 요청 수락과 이벤트 소비를 시작한다");
        }
        drain();
    }

    /**
     * 준비되면 실행할 작업을 건다. 이미 준비됐으면 호출 스레드에서 즉시 실행한다.
     *
     * <p>등록 후 다시 상태를 확인해 드레인한다 — 등록과 {@link #markReady()}가 엇갈려
     * 콜백이 영원히 큐에 남는 것을 막는다.
     */
    public void whenReady(Runnable action) {
        waiting.add(action);
        drain();
    }

    private void drain() {
        if (!ready.get()) {
            return;
        }
        Runnable action;
        while ((action = waiting.poll()) != null) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("색인 준비 후 대기 작업 실행 실패", e);
            }
        }
    }
}
