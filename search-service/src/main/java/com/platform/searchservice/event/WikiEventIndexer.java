package com.platform.searchservice.event;

import com.platform.proto.events.v1.EventEnvelope;
import com.platform.searchservice.content.WikiContentClient;
import com.platform.searchservice.index.IndexingResult;
import com.platform.searchservice.index.OpenSearchIndexService;
import com.platform.searchservice.index.WikiDocuments;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** EventEnvelope의 wiki 이벤트를 OpenSearch 투영 연산으로 바꾼다. */
@Component
@RequiredArgsConstructor
public class WikiEventIndexer {

    private final WikiContentClient content;
    private final OpenSearchIndexService indexes;

    public void handle(EventEnvelope event) {
        long occurredAt = event.getOccurredAt();
        switch (event.getPayloadCase()) {
            case PAGE_CREATED -> upsertOrDeletePage(event.getPageCreated().getPageId(), occurredAt);
            case PAGE_UPDATED -> upsertOrDeletePage(event.getPageUpdated().getPageId(), occurredAt);
            case PAGE_DELETED -> deletePageAndAttachments(event.getPageDeleted().getPageId(), occurredAt);
            case SPACE_CREATED -> {
                // 스페이스 자체는 검색 문서가 아니며 첫 페이지/첨부 이벤트가 표시값을 채운다.
            }
            case SPACE_UPDATED -> indexes.updateSpace(
                    event.getSpaceUpdated().getSpaceId(),
                    event.getSpaceUpdated().getName(),
                    event.getSpaceUpdated().getKey());
            case SPACE_DELETED -> indexes.deleteBySpaceId(event.getSpaceDeleted().getSpaceId());
            case ATTACHMENT_ADDED -> upsertOrDeleteAttachment(
                    event.getAttachmentAdded().getAttachmentId(), occurredAt);
            case ATTACHMENT_DELETED -> indexes.deleteAttachment(
                    event.getAttachmentDeleted().getAttachmentId(), occurredAt);
            case PROJECT_CREATED, PROJECT_UPDATED, PROJECT_DELETED,
                    ISSUE_CREATED, ISSUE_UPDATED, ISSUE_DELETED -> throw new IllegalArgumentException(
                    "ALM 이벤트가 wiki 색인기로 라우팅됐습니다: " + event.getPayloadCase());
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException(
                    "payload가 없는 EventEnvelope: eventId=" + event.getEventId());
        }
    }

    private void upsertOrDeletePage(long pageId, long occurredAt) {
        content.getPage(pageId).ifPresentOrElse(
                page -> indexes.upsertPage(WikiDocuments.toDocument(page), occurredAt),
                () -> deletePageAndAttachments(pageId, occurredAt));
    }

    private void deletePageAndAttachments(long pageId, long occurredAt) {
        IndexingResult result = indexes.deletePage(pageId, occurredAt);
        // 과거 PageDeleted가 최신 페이지를 이기지 못했는데 자식만 지우면 최신 첨부가 사라진다.
        if (result == IndexingResult.APPLIED) {
            indexes.deleteAttachmentsByPageId(pageId);
        }
    }

    private void upsertOrDeleteAttachment(long attachmentId, long occurredAt) {
        content.getAttachment(attachmentId).ifPresentOrElse(
                attachment -> indexes.upsertAttachment(WikiDocuments.toDocument(attachment), occurredAt),
                () -> indexes.deleteAttachment(attachmentId, occurredAt));
    }

}
