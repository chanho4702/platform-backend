package com.platform.searchservice.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.transport.httpclient5.ResponseException;
import org.opensearch.client.opensearch._types.VersionType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Objects;

/** wiki 도메인 이벤트를 별칭 뒤의 OpenSearch 문서로 투영한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenSearchIndexService {
    private static final int HTTP_CONFLICT = 409;

    /**
     * The Java client reports API errors as {@link OpenSearchException}, but the HttpClient 5
     * transport can surface a non-deserialized response (including external-version conflicts)
     * as {@link ResponseException}. Inspect the cause chain because synchronous client calls may
     * wrap either transport exception before it reaches this boundary.
     *
     * <p>Requests intentionally use {@code external}, not {@code external_gte}: an equal version is
     * a duplicate and must not rewrite the document (especially with a different payload). Mapping
     * its 409 to {@link IndexingResult#VERSION_CONFLICT} makes that no-op acknowledgeable by the
     * consumer while retaining strict monotonic writes.
     */
    private static boolean isVersionConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenSearchException openSearchException
                    && openSearchException.status() == HTTP_CONFLICT) {
                return true;
            }
            if (cause instanceof ResponseException responseException
                    && responseException.status() == HTTP_CONFLICT) {
                return true;
            }
        }
        return false;
    }


    private static final String SPACE_UPDATE_SCRIPT = """
            ctx._source.spaceName = params.spaceName;
            ctx._source.spaceKey = params.spaceKey;
            """;

    private final OpenSearchClient client;

    public IndexingResult upsertPage(PageDoc document, long occurredAt) {
        Objects.requireNonNull(document, "document");
        return versioned("page upsert", IndexNames.pageDocId(document.pageId()), occurredAt, () ->
                client.index(i -> i
                        .index(IndexNames.PAGE_ALIAS)
                        .id(IndexNames.pageDocId(document.pageId()))
                        .document(document)
                        .version(occurredAt)
                        .versionType(VersionType.External)));
    }

    public IndexingResult deletePage(long pageId, long occurredAt) {
        return versioned("page delete", IndexNames.pageDocId(pageId), occurredAt, () ->
                client.delete(d -> d
                        .index(IndexNames.PAGE_ALIAS)
                        .id(IndexNames.pageDocId(pageId))
                        .version(occurredAt)
                        .versionType(VersionType.External)));
    }

    public IndexingResult upsertAttachment(AttachmentDoc document, long occurredAt) {
        Objects.requireNonNull(document, "document");
        return versioned("attachment upsert", IndexNames.attachmentDocId(document.attachmentId()), occurredAt, () ->
                client.index(i -> i
                        .index(IndexNames.ATTACHMENT_ALIAS)
                        .id(IndexNames.attachmentDocId(document.attachmentId()))
                        .document(document)
                        .version(occurredAt)
                        .versionType(VersionType.External)));
    }

    public IndexingResult deleteAttachment(long attachmentId, long occurredAt) {
        return versioned("attachment delete", IndexNames.attachmentDocId(attachmentId), occurredAt, () ->
                client.delete(d -> d
                        .index(IndexNames.ATTACHMENT_ALIAS)
                        .id(IndexNames.attachmentDocId(attachmentId))
                        .version(occurredAt)
                        .versionType(VersionType.External)));
    }

    /** PageDeleted가 개별 AttachmentDeleted를 만들지 않으므로 소속 첨부를 한 번에 정리한다. */
    public long deleteAttachmentsByPageId(long pageId) {
        try {
            var response = client.deleteByQuery(d -> d
                    .index(IndexNames.ATTACHMENT_ALIAS)
                    .query(q -> q.term(t -> t.field("pageId").value(FieldValue.of(pageId))))
                    .refresh(true));
            return valueOrZero(response.deleted());
        } catch (Exception e) {
            throw failure("attachment delete_by_query(page_id)", String.valueOf(pageId), e);
        }
    }

    /** SpaceDeleted는 자식별 이벤트를 내지 않으므로 두 별칭의 파생 문서를 함께 정리한다. */
    public long deleteBySpaceId(long spaceId) {
        try {
            var response = client.deleteByQuery(d -> d
                    .index(IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS)
                    .query(q -> q.term(t -> t.field("spaceId").value(FieldValue.of(spaceId))))
                    .refresh(true));
            return valueOrZero(response.deleted());
        } catch (Exception e) {
            throw failure("delete_by_query(space_id)", String.valueOf(spaceId), e);
        }
    }

    /** 비정규화된 스페이스 표시값을 페이지와 첨부 문서 모두에서 갱신한다. */
    public long updateSpace(long spaceId, String spaceName, String spaceKey) {
        Objects.requireNonNull(spaceName, "spaceName");
        Objects.requireNonNull(spaceKey, "spaceKey");
        try {
            var response = client.updateByQuery(u -> u
                    .index(IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS)
                    .query(q -> q.term(t -> t.field("spaceId").value(FieldValue.of(spaceId))))
                    .script(s -> s.inline(i -> i
                            .lang("painless")
                            .source(SPACE_UPDATE_SCRIPT)
                            .params("spaceName", JsonData.of(spaceName))
                            .params("spaceKey", JsonData.of(spaceKey))))
                    .refresh(true));
            return valueOrZero(response.updated());
        } catch (Exception e) {
            throw failure("update_by_query(space_id)", String.valueOf(spaceId), e);
        }
    }

    private IndexingResult versioned(String operation, String id, long occurredAt, IoOperation request) {
        if (occurredAt <= 0) {
            throw new IllegalArgumentException("occurredAt은 양수 epoch millis여야 합니다");
        }
        try {
            request.execute();
            return IndexingResult.APPLIED;
        } catch (OpenSearchException e) {
            if (e.status() == 409 && "version_conflict_engine_exception".equals(e.error().type())) {
                log.info("과거 또는 중복 이벤트 무시: operation={} id={} occurredAt={}", operation, id, occurredAt);
                return IndexingResult.VERSION_CONFLICT;
            }
            throw failure(operation, id, e);
        } catch (Exception e) {
            // 실전에서 실제로 타는 분기다 — 트랜스포트가 OpenSearchException으로 감싸주지 않는다.
            // 위 분기와 같은 수준으로 남겨야 "왜 색인이 안 됐나"를 로그만으로 판단할 수 있다.
            if (isVersionConflict(e)) {
                log.info("과거 또는 중복 이벤트 무시: operation={} id={} occurredAt={}", operation, id, occurredAt);
                return IndexingResult.VERSION_CONFLICT;
            }
            throw failure(operation, id, e);
        }
    }

    private IndexingException failure(String operation, String id, Exception cause) {
        log.error("OpenSearch 색인 연산 실패: operation={} id={}", operation, id, cause);
        return new IndexingException(operation + " 실패: id=" + id, cause);
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    @FunctionalInterface
    private interface IoOperation {
        void execute() throws IOException;
    }
}
