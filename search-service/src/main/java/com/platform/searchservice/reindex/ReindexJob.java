package com.platform.searchservice.reindex;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 잡 하나의 가변 상태. 상태는 메모리에만 둔다 — 재기동하면 잃지만, 재색인은 다시 돌리면 되는
 * 조작이라 수용한다(설계 §9).
 *
 * 진행 카운터를 작업 스레드가 쓰고 상태 조회 스레드가 읽으므로 volatile/atomic으로 공개한다.
 */
final class ReindexJob {

    private final String jobId;
    private final String pageIndex;
    private final String attachmentIndex;
    private final Instant startedAt;
    private final AtomicLong pagesIndexed = new AtomicLong();
    private final AtomicLong attachmentsIndexed = new AtomicLong();

    private volatile ReindexState state = ReindexState.RUNNING;
    private volatile boolean aliasSwitched;
    private volatile Instant finishedAt;
    private volatile String failure;

    ReindexJob(String jobId, String pageIndex, String attachmentIndex, Instant startedAt) {
        this.jobId = jobId;
        this.pageIndex = pageIndex;
        this.attachmentIndex = attachmentIndex;
        this.startedAt = startedAt;
    }

    String jobId() {
        return jobId;
    }

    String pageIndex() {
        return pageIndex;
    }

    String attachmentIndex() {
        return attachmentIndex;
    }

    void addPages(long count) {
        pagesIndexed.addAndGet(count);
    }

    void addAttachments(long count) {
        attachmentsIndexed.addAndGet(count);
    }

    /**
     * 별칭 전환까지 끝났을 때만 호출한다.
     *
     * **{@code state}를 맨 마지막에 쓴다.** 종료 상태가 먼저 보이면, 그걸 보고 "끝났다"고 판단한
     * 조회 스레드가 아직 채워지지 않은 finishedAt(null)이나 aliasSwitched(false)를 함께 읽는다.
     * volatile 쓰기 앞의 쓰기는 그 volatile을 읽은 스레드에 모두 보이므로(happens-before),
     * 종료 상태를 마지막에 쓰면 그 상태를 본 독자는 종료 메타데이터도 반드시 함께 본다.
     */
    void succeeded(Instant at) {
        this.aliasSwitched = true;
        this.finishedAt = at;
        this.state = ReindexState.SUCCEEDED;
    }

    /** {@link #succeeded}와 같은 이유로 {@code state}가 마지막 쓰기다. */
    void failed(Instant at, String reason) {
        this.finishedAt = at;
        this.failure = reason;
        this.state = ReindexState.FAILED;
    }

    ReindexState state() {
        return state;
    }

    ReindexJobView view() {
        return new ReindexJobView(
                jobId, state, aliasSwitched,
                pagesIndexed.get(), attachmentsIndexed.get(),
                pageIndex, attachmentIndex,
                startedAt, finishedAt, failure);
    }
}
