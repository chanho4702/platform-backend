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
        String pageIndex,
        String attachmentIndex,
        Instant startedAt,
        Instant finishedAt,
        String failure
) {}
