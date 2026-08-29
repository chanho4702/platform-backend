package com.platform.searchservice.search;

import com.platform.searchservice.common.ServiceUnavailableException;
import com.platform.searchservice.index.IndexNames;
import com.platform.searchservice.permission.AccessScope;
import org.opensearch.client.opensearch._types.SortOrder;
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
    private static final int FILTER_BATCH_SIZE = 100;
    private static final int MAX_FILTER_SCAN = 10_000;

    private final OpenSearchClient client;
    private final PermissionClient permissions;
    private final com.platform.searchservice.content.WikiContentClient wikiContent;

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
        int visibleOffset = safeOffset(input.normalizedPage(), size);
        Query query = buildQuery(input, needsSpaceFilter ? effectiveSpaces : Set.of());

        // 제한 필터를 통과한 순서 기준으로 페이지를 만들어야 첫 raw 페이지의 제한 문서가
        // 공개 문서를 다음 페이지로 밀어내지 않는다. 한 건을 더 찾으면 다음 페이지 존재도 안다.
        int targetVisible = (int) Math.min((long) visibleOffset + size + 1L, Integer.MAX_VALUE);
        List<SearchHit> visible = new ArrayList<>();
        int rawFrom = 0;
        long tookMs = 0L;
        boolean exhausted = false;
        try {
            while (!exhausted && visible.size() < targetVisible && rawFrom < MAX_FILTER_SCAN) {
                SearchResponse<Map> response =
                        executeSearch(query, input.normalizedSort(), rawFrom, FILTER_BATCH_SIZE);
                tookMs += response.took();
                List<SearchHit> rawHits = response.hits().hits().stream().map(this::toSearchHit).toList();
                visible.addAll(filterRestricted(userId, rawHits));

                rawFrom += rawHits.size();
                long rawTotal = response.hits().total() == null ? rawFrom : response.hits().total().value();
                exhausted = rawHits.isEmpty() || rawFrom >= rawTotal;
            }
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenSearch 검색 실패: user={} query={}", userId, input.query(), e);
            throw new ServiceUnavailableException("검색 엔진에 연결할 수 없습니다", e);
        }

        int pageEnd = (int) Math.min(visible.size(), (long) visibleOffset + size);
        List<SearchHit> pageHits = visibleOffset >= visible.size()
                ? List.of()
                : List.copyOf(visible.subList(visibleOffset, pageEnd));
        boolean totalExact = exhausted;
        long reportedTotal = totalExact
                ? visible.size()
                : Math.max(visible.size(), (long) pageEnd + 1L);
        return new SearchResults(toGraphQlInt(reportedTotal), totalExact, toGraphQlInt(tookMs), pageHits);
    }

    private SearchResponse<Map> executeSearch(Query query, SearchSort sort, int from, int size)
            throws java.io.IOException {
        return client.search(s -> {
                    s
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
                                        .numberOfFragments(HIGHLIGHT_FRAGMENT_COUNT)));
                    // 관련도는 OpenSearch 기본 정렬(_score)이라 절을 붙이지 않는다 — 붙이면
                    // 동점 처리까지 우리가 떠안는다. 날짜 정렬만 명시한다.
                    if (sort == SearchSort.UPDATED_DESC) {
                        s.sort(so -> so.field(f -> f.field("updatedAt").order(SortOrder.Desc)));
                    } else if (sort == SearchSort.UPDATED_ASC) {
                        s.sort(so -> so.field(f -> f.field("updatedAt").order(SortOrder.Asc)));
                    }
                    return s;
                },
                Map.class);
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
        Set<Long> authors = input.requestedAuthorIds();
        if (!authors.isEmpty()) {
            filters.add(terms("authorId", authors.stream().map(id -> FieldValue.of(id.longValue())).toList()));
        }
        List<String> labels = input.normalizedLabels();
        if (!labels.isEmpty()) {
            filters.add(terms("labels", labels.stream().map(FieldValue::of).toList()));
        }
        Long after = input.updatedAfterMillis();
        Long before = input.updatedBeforeMillis();
        if (after != null || before != null) {
            filters.add(updatedRange(after, before));
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

    /**
     * updatedAt 범위. 매핑이 `date` + `epoch_millis`라 경계도 밀리초 숫자로 보낸다.
     * 경계는 포함(gte/lte)이다 — "8월 1일부터"가 8월 1일 문서를 빼면 사용자가 이유를 알 수 없다.
     */
    private static Query updatedRange(Long after, Long before) {
        return Query.of(q -> q.range(r -> {
            r.field("updatedAt");
            if (after != null) r.gte(org.opensearch.client.json.JsonData.of(after));
            if (before != null) r.lte(org.opensearch.client.json.JsonData.of(before));
            return r;
        }));
    }

    private static Query terms(String field, List<FieldValue> values) {
        return Query.of(q -> q.terms(t -> t.field(field).terms(v -> v.value(values))));
    }

    /** PAGE는 자신, ATTACHMENT는 소속 페이지 기준으로 wiki 권한 필터를 통과해야 남는다. */
    private List<SearchHit> filterRestricted(long userId, List<SearchHit> hits) {
        Set<Long> pageIds = new java.util.HashSet<>();
        for (SearchHit h : hits) {
            Long pid = ownerPageId(h);
            if (pid != null) pageIds.add(pid);
        }
        if (pageIds.isEmpty()) return hits;
        Set<Long> visible = wikiContent.filterVisiblePages(userId, pageIds);
        return hits.stream().filter(h -> {
            Long pid = ownerPageId(h);
            return pid == null || visible.contains(pid);
        }).toList();
    }

    private static Long ownerPageId(SearchHit h) {
        String raw = h.docType() == DocType.PAGE ? h.id() : h.pageId();
        if (raw == null) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
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
                pageType(source),
                nullableText(source, "title"),
                nullableText(source, "filename"),
                highlights,
                instantText(source.get("updatedAt")),
                score == null ? 0.0 : score);
    }

    /** 색인의 `type`(page·folder)을 계약의 PageType으로 옮긴다. 첨부에는 없는 필드라 null이다. */
    private static String pageType(java.util.Map<String, Object> source) {
        String type = nullableText(source, "type");
        return type == null ? null : type.toUpperCase(java.util.Locale.ROOT);
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
