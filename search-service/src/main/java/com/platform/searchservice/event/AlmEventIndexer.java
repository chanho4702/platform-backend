package com.platform.searchservice.event;

import com.platform.proto.events.v1.EventEnvelope;
import com.platform.searchservice.content.AlmContentClient;
import com.platform.searchservice.index.AlmDocuments;
import com.platform.searchservice.index.OpenSearchIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** ALM 프로젝트·이슈 이벤트를 OpenSearch 투영 연산으로 바꾼다. */
@Component
@RequiredArgsConstructor
public class AlmEventIndexer {

    private final AlmContentClient content;
    private final OpenSearchIndexService indexes;

    public void handle(EventEnvelope event) {
        long occurredAt = event.getOccurredAt();
        switch (event.getPayloadCase()) {
            case PROJECT_CREATED -> {
                // 프로젝트 자체는 검색 문서가 아니며 첫 이슈가 표시값을 채운다.
            }
            case PROJECT_UPDATED -> indexes.updateProject(
                    event.getProjectUpdated().getProjectId(),
                    event.getProjectUpdated().getName(),
                    event.getProjectUpdated().getKey());
            case PROJECT_DELETED -> indexes.deleteIssuesByProjectId(
                    event.getProjectDeleted().getProjectId());
            case ISSUE_CREATED -> upsertOrDelete(event.getIssueCreated().getIssueId(), occurredAt);
            case ISSUE_UPDATED -> upsertOrDelete(event.getIssueUpdated().getIssueId(), occurredAt);
            case ISSUE_DELETED -> indexes.deleteIssue(event.getIssueDeleted().getIssueId(), occurredAt);
            case SPACE_CREATED, SPACE_UPDATED, SPACE_DELETED,
                    PAGE_CREATED, PAGE_UPDATED, PAGE_DELETED,
                    ATTACHMENT_ADDED, ATTACHMENT_DELETED -> throw new IllegalArgumentException(
                    "wiki 이벤트가 ALM 색인기로 라우팅됐습니다: " + event.getPayloadCase());
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException(
                    "payload가 없는 EventEnvelope: eventId=" + event.getEventId());
        }
    }

    private void upsertOrDelete(long issueId, long occurredAt) {
        content.getIssue(issueId).ifPresentOrElse(
                issue -> indexes.upsertIssue(AlmDocuments.toDocument(issue), occurredAt),
                () -> indexes.deleteIssue(issueId, occurredAt));
    }
}
