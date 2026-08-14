package com.platform.searchservice.reindex;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종료 상태의 **가시성** 계약.
 *
 * 잡을 쓰는 스레드(재색인 실행기)와 읽는 스레드(상태 조회 요청)가 다르다. 종료 상태가 종료
 * 메타데이터보다 먼저 보이면, 그걸 "끝났다"로 읽은 조회가 finishedAt=null·aliasSwitched=false를
 * 함께 돌려준다 — 운영자에게는 "성공했는데 끝난 시각이 없는" 응답이 된다.
 *
 * 그래서 상태를 **마지막에** 쓴다. 아래 두 테스트는 종료 상태를 처음 관측한 순간의 스냅샷을 잡아
 * 메타데이터가 이미 채워져 있는지 본다.
 */
class ReindexJobTest {

    /** 관측 창이 좁아 한 번으로는 못 잡는다 — 반복해서 교차 실행 지점을 훑는다. */
    private static final int ROUNDS = 2_000;

    @Test
    void 성공_상태가_보이는_순간_별칭전환과_종료시각도_함께_보인다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            ReindexJobView observed = raceUntilTerminal(job -> job.succeeded(Instant.now()));

            assertThat(observed.state()).isEqualTo(ReindexState.SUCCEEDED);
            assertThat(observed.aliasSwitched()).isTrue();
            assertThat(observed.finishedAt()).isNotNull();
        }
    }

    @Test
    void 실패_상태가_보이는_순간_실패사유와_종료시각도_함께_보인다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            ReindexJobView observed = raceUntilTerminal(job -> job.failed(Instant.now(), "첨부 백필 중단"));

            assertThat(observed.state()).isEqualTo(ReindexState.FAILED);
            assertThat(observed.finishedAt()).isNotNull();
            assertThat(observed.failure()).isEqualTo("첨부 백필 중단");
        }
    }

    /**
     * 쓰기 스레드가 종료 처리를 하는 동안 읽기 스레드가 state를 회전 관측하다가, 종료 상태를
     * **처음 본 순간의** view를 그대로 잡아 돌려준다.
     */
    private static ReindexJobView raceUntilTerminal(java.util.function.Consumer<ReindexJob> terminate)
            throws Exception {
        ReindexJob job = new ReindexJob("job", "wiki-page-v2", "wiki-attachment-v2", Instant.now());
        CountDownLatch ready = new CountDownLatch(2);
        AtomicReference<ReindexJobView> firstTerminalView = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            ready.countDown();
            await(ready);
            while (job.state() == ReindexState.RUNNING) {
                Thread.onSpinWait();
            }
            firstTerminalView.set(job.view());
        });
        Thread writer = new Thread(() -> {
            ready.countDown();
            await(ready);
            terminate.accept(job);
        });

        reader.start();
        writer.start();
        writer.join(10_000);
        reader.join(10_000);
        return firstTerminalView.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
