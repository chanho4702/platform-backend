package com.platform.searchservice.search;

import com.platform.searchservice.common.ServiceUnavailableException;
import com.platform.searchservice.index.IndexNames;
import com.platform.searchservice.permission.AccessScope;
import com.platform.searchservice.permission.PermissionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenSearchQueryService {

    private static final String DRAFT_STATUS = "draft";
    private static final int HIGHLIGHT_FRAGMENT_SIZE = 160;
    private static final int HIGHLIGHT_FRAGMENT_COUNT = 3;

    private final OpenSearchClient client;
    private final PermissionClient permissions;

    public SearchResults search(long userId, SearchInput input) {
        AccessScope scope = permissions.accessibleSpaces(userId);
        Set<Long> requestedSpaces = input.requestedSpaceIds();
        Set<Long> effectiveSpaces = scope.resolveFilter(requestedSpaces);
        boolean needsSpaceFilter = !scope.all() || !requestedSpaces.isEmpty();

        // terms []는 버전별 해석에 맡기지 않는다. 권한 0건과 요청 교집합 0건은 OpenSearch까지
        // 보내지 않아야 권한 경계가 질의 구성 실수와 무관하게 닫힌다.
        if (scope.isEmpty() || (needsSpaceFilter && effectiveSpaces.isEmpty())) {
            return SearchResults.empty();
        }

        int size = input.normalizedSize();
        int from = safeOffset(input.normalizedPage(), size);
        Query query = buildQuery(input, needsSpaceFilter ? effectiveSpaces : Set.of());

        SearchResponse<Map> response;
        try {
            response = client.search(s -> s
                            // 읽기 별칭만 사용한다. 재색인 때 물리 인덱스가 바뀌어도 검색은 끊기지 않는다.
                            .index(List.of(IndexNames.SEARCH_TARGETS))
                            .from(from)
                            .size(size)
                            .trackTotalHits(t -> t.enabled(true))
                            .query(query)
                            .highlight(h -> h
                                    .fields("title", f -> f
                                            .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                            .numberOfFragments(HIGHLIGHT_FRAGMENT_COUNT))
                                    .fields("content", f -> f
                                            .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                            .numberOfFragments(HIGHLIGHT_FRAGMENT_COUNT))),
                    Map.class);
        } catch (Exception e) {
            log.error("OpenSearch 검색 실패: user={} query={}", userId, input.query(), e);
            throw new ServiceUnavailableException("검색 엔진에 연결할 수 없습니다", e);
        }

        long total = response.hits().total() == null ? 0L : response.hits().total().value();
        List<SearchHit> hits = response.hits().hits().stream().map(this::toSearchHit).toList();
        return new SearchResults(toGraphQlInt(total), toGraphQlInt(response.took()), hits);
    }

    private static Query buildQuery(SearchInput input, Set<Long> effectiveSpaces) {
        List<Query> filters = new ArrayList<>();
        if (!effectiveSpaces.isEmpty()) {
            filters.add(terms("spaceId", effectiveSpaces.stream().map(spaceId -> FieldValue.of(spaceId.longValue())).toList()));
        }
        if (input.docTypes() != null && !input.docTypes().isEmpty()) {
            filters.add(terms("docType", input.docTypes().stream()
                    .map(type -> FieldValue.of(type.name()))
                    .toList()));
        }

        Query textMatch = Query.of(q -> q.multiMatch(m -> m
                // 제목을 본문보다 명시적으로 높인다. filename은 첨부 인덱스에서만 존재한다.
                .fields("title^3", "content", "filename")
                .query(input.query())));

        return Query.of(q -> q.bool(b -> {
            b.must(textMatch);
            if (!filters.isEmpty()) b.filter(filters);
            if (!input.draftsIncluded()) {
                // 첨부에는 status 필드가 없으므로 이 must_not은 페이지 초안에만 걸린다.
                b.mustNot(n -> n.term(t -> t.field("status").value(FieldValue.of(DRAFT_STATUS))));
            }
            return b;
        }));
    }

    private static Query terms(String field, List<FieldValue> values) {
        return Query.of(q -> q.terms(t -> t.field(field).terms(v -> v.value(values))));
    }

    private SearchHit toSearchHit(Hit<Map> hit) {
        Map<String, Object> source = hit.source();
        if (source == null) throw new IllegalStateException("검색 hit에 _source가 없습니다: " + hit.id());

        DocType docType = DocType.valueOf(text(source, "docType"));
        String id = docType == DocType.PAGE
                ? numberText(source, "pageId")
                : numberText(source, "attachmentId");
        String pageId = docType == DocType.ATTACHMENT ? numberText(source, "pageId") : null;
        List<String> highlights = List.of("title", "content").stream()
                .flatMap(field -> hit.highlight().getOrDefault(field, List.of()).stream())
                .toList();

        Double score = hit.score();
        return new SearchHit(
                id,
                docType,
                numberText(source, "spaceId"),
                text(source, "spaceKey"),
                text(source, "spaceName"),
                pageId,
                nullableText(source, "title"),
                nullableText(source, "filename"),
                highlights,
                instantText(source.get("updatedAt")),
                score == null ? 0.0 : score);
    }

    private static int safeOffset(int page, int size) {
        return (int) Math.min((long) page * size, Integer.MAX_VALUE);
    }

    private static int toGraphQlInt(long value) {
        return (int) Math.min(Math.max(value, 0L), Integer.MAX_VALUE);
    }

    private static String text(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (value == null) throw new IllegalStateException("검색 hit 필드가 없습니다: " + field);
        return String.valueOf(value);
    }

    private static String nullableText(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static String numberText(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("검색 hit 숫자 필드가 없습니다: " + field);
        }
        return String.valueOf(number.longValue());
    }

    private static String instantText(Object value) {
        return value instanceof Number number ? Instant.ofEpochMilli(number.longValue()).toString() : null;
    }
}
