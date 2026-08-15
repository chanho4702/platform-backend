package com.platform.searchservice.reindex;

import java.time.Instant;

/**
 * 재색인 잡의 외부 표현(관리자 REST 응답).
 *
 * {@code pagesIndexed}/{@code attachmentsIndexed}는 RUNNING·FAILED 동안에는 **진행량**일 뿐
 * 결과가 아니다. 결과인지 여부는 {@code state}와 {@code aliasSwitched}로만 판단한다.
 */
public record ReindexJobView(
        String jobId,
        ReindexState state,
        boolean aliasSwitched,
        long pagesIndexed,
        long attachmentsIndexed,
        long issuesIndexed,
        String pageIndex,
        String attachmentIndex,
        String issueIndex,
        Instant startedAt,
        Instant finishedAt,
        String failure
) {
    /** Wave C 관리자 응답을 만들던 테스트/호출부와 소스 호환. */
    public ReindexJobView(
            String jobId,
            ReindexState state,
            boolean aliasSwitched,
            long pagesIndexed,
            long attachmentsIndexed,
            String pageIndex,
            String attachmentIndex,
            Instant startedAt,
            Instant finishedAt,
            String failure) {
        this(jobId, state, aliasSwitched, pagesIndexed, attachmentsIndexed, 0L,
                pageIndex, attachmentIndex, null, startedAt, finishedAt, failure);
    }
}
