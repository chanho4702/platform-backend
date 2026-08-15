package com.platform.searchservice.index;

import com.platform.proto.alm.v1.IssueContent;

import java.util.Locale;

/** ALM gRPC 계약을 이벤트 소비와 재색인이 함께 쓰는 색인 문서로 변환한다. */
public final class AlmDocuments {

    private AlmDocuments() {}

    public static IssueDoc toDocument(IssueContent issue) {
        return new IssueDoc(
                IssueDoc.DOC_TYPE,
                issue.getIssueId(),
                issue.getProjectId(),
                issue.getProjectKey(),
                issue.getProjectName(),
                issue.getIssueKey(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getType().toLowerCase(Locale.ROOT),
                issue.getStatus().toLowerCase(Locale.ROOT),
                issue.getPriority().toLowerCase(Locale.ROOT),
                issue.hasAssigneeId() ? issue.getAssigneeId() : null,
                issue.getReporterId(),
                issue.getVersion(),
                issue.getUpdatedAt());
    }
}
