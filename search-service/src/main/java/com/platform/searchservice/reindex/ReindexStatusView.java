package com.platform.searchservice.reindex;

/**
 * 색인 현황(관리 REST 응답).
 *
 * 문서 수를 세는 이유는 "재색인이 필요한가"를 눈으로 확인할 근거이기 때문이다 — 세대 번호가
 * 그대로인데 새 필드를 기대하고 있으면 검색은 조용히 0건을 낸다.
 *
 * {@code runningJob}이 null이 아니면 지금 재색인이 돌고 있다.
 */
public record ReindexStatusView(
        String pageIndex,
        String attachmentIndex,
        long pageDocs,
        long attachmentDocs,
        ReindexJobView runningJob) {
}
