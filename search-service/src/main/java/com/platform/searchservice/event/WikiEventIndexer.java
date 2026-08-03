package com.platform.searchservice.event;

import com.platform.proto.events.v1.EventEnvelope;
import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.searchservice.content.WikiContentClient;
import com.platform.searchservice.index.AttachmentDoc;
import com.platform.searchservice.index.IndexingResult;
import com.platform.searchservice.index.OpenSearchIndexService;
import com.platform.searchservice.index.PageDoc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

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
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException(
                    "payload가 없는 EventEnvelope: eventId=" + event.getEventId());
        }
    }

    private void upsertOrDeletePage(long pageId, long occurredAt) {
        content.getPage(pageId).ifPresentOrElse(
                page -> indexes.upsertPage(toDocument(page), occurredAt),
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
                attachment -> indexes.upsertAttachment(toDocument(attachment), occurredAt),
                () -> indexes.deleteAttachment(attachmentId, occurredAt));
    }

    private static PageDoc toDocument(PageContent page) {
        return new PageDoc(
                PageDoc.DOC_TYPE,
                page.getPageId(),
                page.getSpaceId(),
                page.getSpaceKey(),
                page.getSpaceName(),
                page.getTitle(),
                page.getContent(),
                page.getType().name().toLowerCase(Locale.ROOT),
                page.getStatus().name().toLowerCase(Locale.ROOT),
                page.getVersion(),
                page.getAuthorId(),
                page.getUpdatedAt());
    }

    private static AttachmentDoc toDocument(AttachmentMeta attachment) {
        return new AttachmentDoc(
                AttachmentDoc.DOC_TYPE,
                attachment.getAttachmentId(),
                attachment.getPageId(),
                attachment.getSpaceId(),
                attachment.getSpaceKey(),
                attachment.getSpaceName(),
                attachment.getFilename(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getUploadedBy(),
                attachment.getCreatedAt());
    }
}
