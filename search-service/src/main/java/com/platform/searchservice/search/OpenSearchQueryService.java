package com.platform.searchservice.search;

import com.platform.searchservice.common.ServiceUnavailableException;
import com.platform.searchservice.index.IndexNames;
import com.platform.searchservice.permission.PermissionClient;
import com.platform.searchservice.permission.SearchAccessScope;
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
import java.util.Locale;
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
        SearchAccessScope scope = permissions.accessibleResources(userId);
        Set<Long> requestedSpaces = input.requestedSpaceIds();
        Set<Long> requestedProjects = input.requestedProjectIds();
        Set<Long> effectiveSpaces = scope.resolveSpaces(requestedSpaces);
        Set<Long> effectiveProjects = scope.resolveProjects(requestedProjects);
        boolean needsSpaceFilter = !scope.all() || !requestedSpaces.isEmpty();
        boolean needsProjectFilter = !scope.all() || !requestedProjects.isEmpty();
        Set<DocType> requestedTypes = input.docTypes() == null || input.docTypes().isEmpty()
                ? Set.of(DocType.values())
                : Set.copyOf(input.docTypes());
        boolean wantsWiki = requestedTypes.contains(DocType.PAGE)
                || requestedTypes.contains(DocType.ATTACHMENT);
        boolean wantsIssues = requestedTypes.contains(DocType.ISSUE);
        boolean wikiAllowed = wantsWiki && (!needsSpaceFilter || !effectiveSpaces.isEmpty());
        boolean issuesAllowed = wantsIssues && (!needsProjectFilter || !effectiveProjects.isEmpty());

        // 한 도메인의 권한이 0건이어도 다른 도메인은 검색할 수 있다. 둘 다 닫혔을 때만 단락한다.
        if (!wikiAllowed && !issuesAllowed) {
            return SearchResults.empty();
        }

        int size = input.normalizedSize();
        int from = safeOffset(input.normalizedPage(), size);
        Query query = buildQuery(
                input,
                requestedTypes,
                wikiAllowed,
                needsSpaceFilter ? effectiveSpaces : Set.of(),
                issuesAllowed,
                needsProjectFilter ? effectiveProjects : Set.of());

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
                                            .numberOfFragments(HIGHLIGHT_FRAGMENT_COUNT))
                                    .fields("filename", f -> f
                                            .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)
                                            .numberOfFragments(HIGHLIGHT_FRAGMENT_COUNT))
                                    .fields("issueKey", f -> f
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

    private static Query buildQuery(
            SearchInput input,
            Set<DocType> requestedTypes,
            boolean wikiAllowed,
            Set<Long> effectiveSpaces,
            boolean issuesAllowed,
            Set<Long> effectiveProjects) {
        Query textMatch = Query.of(q -> q.multiMatch(m -> m
                .fields("issueKey^4", "title^3", "projectName^2", "content", "filename")
                .query(input.query())));

        List<Query> domainBranches = new ArrayList<>();
        if (wikiAllowed) {
            List<FieldValue> wikiTypes = requestedTypes.stream()
                    .filter(type -> type == DocType.PAGE || type == DocType.ATTACHMENT)
                    .map(type -> FieldValue.of(type.name()))
                    .toList();
            domainBranches.add(Query.of(q -> q.bool(b -> {
                b.filter(terms("docType", wikiTypes));
                if (!effectiveSpaces.isEmpty()) {
                    b.filter(terms("spaceId", effectiveSpaces.stream().map(FieldValue::of).toList()));
                }
                if (!input.draftsIncluded()) {
                    b.mustNot(n -> n.term(t -> t.field("status").value(FieldValue.of(DRAFT_STATUS))));
                }
                return b;
            })));
        }
        if (issuesAllowed) {
            domainBranches.add(Query.of(q -> q.bool(b -> {
                b.filter(terms("docType", List.of(FieldValue.of(DocType.ISSUE.name()))));
                if (!effectiveProjects.isEmpty()) {
                    b.filter(terms("projectId", effectiveProjects.stream().map(FieldValue::of).toList()));
                }
                return b;
            })));
        }

        return Query.of(q -> q.bool(b -> {
            b.must(textMatch);
            b.filter(f -> f.bool(domains -> domains
                    .should(domainBranches)
                    .minimumShouldMatch("1")));
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
        String id = switch (docType) {
            case PAGE -> numberText(source, "pageId");
            case ATTACHMENT -> numberText(source, "attachmentId");
            case ISSUE -> numberText(source, "issueId");
        };
        String pageId = docType == DocType.ATTACHMENT ? numberText(source, "pageId") : null;
        PageType pageType = docType == DocType.PAGE ? pageType(source) : null;
        List<String> highlights = List.of("title", "content", "filename", "issueKey").stream()
                .flatMap(field -> hit.highlight().getOrDefault(field, List.of()).stream())
                .toList();

        Double score = hit.score();
        return new SearchHit(
                id,
                docType,
                docType == DocType.ISSUE ? null : numberText(source, "spaceId"),
                docType == DocType.ISSUE ? null : text(source, "spaceKey"),
                docType == DocType.ISSUE ? null : text(source, "spaceName"),
                pageId,
                pageType,
                docType == DocType.ISSUE ? numberText(source, "projectId") : null,
                docType == DocType.ISSUE ? text(source, "projectKey") : null,
                docType == DocType.ISSUE ? text(source, "projectName") : null,
                docType == DocType.ISSUE ? text(source, "issueKey") : null,
                docType == DocType.ISSUE ? text(source, "issueType") : null,
                docType == DocType.ISSUE ? text(source, "status") : null,
                docType == DocType.ISSUE ? text(source, "priority") : null,
                nullableText(source, "title"),
                nullableText(source, "filename"),
                highlights,
                instantText(source.get("updatedAt")),
                score == null ? 0.0 : score);
    }

    private static PageType pageType(Map<String, Object> source) {
        String value = nullableText(source, "type");
        // type 필드 도입 전 만들어진 물리 인덱스도 읽기 별칭 롤백 후보가 될 수 있어 PAGE로 호환한다.
        return value == null ? PageType.PAGE : PageType.valueOf(value.toUpperCase(Locale.ROOT));
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
