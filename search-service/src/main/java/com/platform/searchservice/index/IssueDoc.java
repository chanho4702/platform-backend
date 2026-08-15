package com.platform.searchservice.index;

/** ALM 이슈 색인 문서. 필드는 opensearch/alm-issue.json의 strict 매핑과 일치해야 한다. */
public record IssueDoc(
        String docType,
        long issueId,
        long projectId,
        String projectKey,
        String projectName,
        String issueKey,
        String title,
        String content,
        String issueType,
        String status,
        String priority,
        Long assigneeId,
        long reporterId,
        int version,
        long updatedAt
) {
    public static final String DOC_TYPE = "ISSUE";
}
