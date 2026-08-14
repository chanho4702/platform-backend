package com.platform.searchservice.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.transport.httpclient5.ResponseException;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.indices.update_aliases.Action;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

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

    /**
     * 별칭이 현재 가리키는 물리 인덱스 하나.
     *
     * 재색인은 "지금 무엇을 쓰고 있나"에서 다음 세대 이름을 정하므로, 별칭이 둘 이상을 가리키면
     * 추측하지 않고 실패시킨다 — 잘못 고르면 별칭이 반쯤 옮겨간 상태가 만들어진다.
     */
    public String resolveAliasIndex(String alias) {
        try {
            var result = client.indices().getAlias(a -> a.name(alias)).result();
            if (result.size() != 1) {
                throw new IndexingException(
                        "별칭 " + alias + "이 물리 인덱스 " + result.size() + "개를 가리킵니다(1개여야 함)", null);
            }
            return result.keySet().iterator().next();
        } catch (IndexingException e) {
            throw e;
        } catch (Exception e) {
            throw failure("alias 조회", alias, e);
        }
    }

    /**
     * 백필 전용 — **별칭이 아니라 물리 인덱스**에 직접 대량 색인한다.
     *
     * 별칭에 쓰면 재색인 중에도 구 인덱스로 들어가버려 새 인덱스가 영원히 비어 있게 된다.
     * 외부 버전은 문서가 "사실이었던 시각"이다. 이걸 붙이지 않으면 전환 뒤 도착한 **오래된**
     * 이벤트가 방금 조달한 최신 본문을 덮어쓴다(내부 버전은 1에서 시작하므로 무엇이든 이긴다).
     */
    public void bulkIndexPages(String physicalIndex, List<VersionedDoc<PageDoc>> documents) {
        bulk(physicalIndex, documents, document -> IndexNames.pageDocId(document.pageId()));
    }

    public void bulkIndexAttachments(String physicalIndex, List<VersionedDoc<AttachmentDoc>> documents) {
        bulk(physicalIndex, documents, document -> IndexNames.attachmentDocId(document.attachmentId()));
    }

    private <T> void bulk(String physicalIndex, List<VersionedDoc<T>> documents, Function<T, String> id) {
        if (documents.isEmpty()) return;
        try {
            BulkRequest.Builder request = new BulkRequest.Builder().index(physicalIndex);
            for (VersionedDoc<T> document : documents) {
                request.operations(op -> op.index(i -> i
                        .id(id.apply(document.document()))
                        .document(document.document())
                        .version(document.version())
                        .versionType(VersionType.External)));
            }
            BulkResponse response = client.bulk(request.build());
            if (response.errors()) {
                String first = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> item.id() + ": " + item.error().reason())
                        .findFirst()
                        .orElse("(원인 미상)");
                // 부분 성공을 성공으로 넘기면 별칭이 구멍 난 인덱스로 옮겨간다 — 잡 전체를 실패시킨다.
                throw new IndexingException(
                        "대량 색인 일부 실패: index=" + physicalIndex + " first=" + first, null);
            }
        } catch (IndexingException e) {
            throw e;
        } catch (Exception e) {
            throw failure("bulk", physicalIndex, e);
        }
    }

    public void refresh(String... indices) {
        try {
            client.indices().refresh(r -> r.index(List.of(indices)));
        } catch (Exception e) {
            throw failure("refresh", String.join(",", indices), e);
        }
    }

    /**
     * 별칭들을 **한 번의 `_aliases` 호출**로 옮긴다.
     *
     * 별칭마다 따로 호출하면 그 사이에 페이지는 새 인덱스, 첨부는 구 인덱스를 보는 창이 생긴다.
     * `_aliases`는 액션 전부를 원자적으로 적용하므로 그 창이 존재하지 않는다.
     *
     * @param switches 별칭 → 새 물리 인덱스
     */
    public void switchAliases(Map<String, String> switches) {
        try {
            List<Action> actions = new ArrayList<>();
            for (Map.Entry<String, String> entry : switches.entrySet()) {
                String alias = entry.getKey();
                String newIndex = entry.getValue();
                // 구 인덱스는 지우지 않고 별칭만 뗀다 — 되돌리려면 별칭을 다시 붙이면 된다(설계 §9).
                actions.add(Action.of(a -> a.remove(r -> r.alias(alias).index("*").mustExist(false))));
                actions.add(Action.of(a -> a.add(add -> add
                        .alias(alias).index(newIndex).isWriteIndex(true))));
            }
            client.indices().updateAliases(u -> u.actions(actions));
            log.info("검색 별칭 전환 완료: {}", switches);
        } catch (Exception e) {
            throw failure("alias 전환", switches.toString(), e);
        }
    }

    /** 대량 색인 1건 — 문서와 그 문서가 사실이었던 시각(외부 버전). */
    public record VersionedDoc<T>(T document, long version) {}

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
