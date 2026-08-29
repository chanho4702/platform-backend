package com.platform.searchservice.search;

import com.platform.searchservice.permission.AccessScope;
import com.platform.searchservice.permission.PermissionClient;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.util.ObjectBuilder;

import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenSearchQueryServiceTest {

    @Test
    void 접근_가능한_스페이스가_없으면_OpenSearch를_호출하지_않는다() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        PermissionClient permissions = scopedTo(AccessScope.of(Set.of()));
        OpenSearchQueryService search = new OpenSearchQueryService(client, permissions, allVisible());

        SearchResults result = search.search(1L, input(List.of()));

        assertThat(result).isEqualTo(SearchResults.empty());
        verifyNoInteractions(client);
    }

    @Test
    void 요청_spaceIds와_권한의_교집합이_없어도_OpenSearch를_호출하지_않는다() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        PermissionClient permissions = scopedTo(AccessScope.of(Set.of(10L)));
        OpenSearchQueryService search = new OpenSearchQueryService(client, permissions, allVisible());

        SearchResults result = search.search(1L, input(List.of("999")));

        assertThat(result).isEqualTo(SearchResults.empty());
        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 첫_raw배치가_모두_제한이면_다음_배치를_읽어_공개_결과로_페이지를_채운다() throws Exception {
        OpenSearchClient client = mock(OpenSearchClient.class);
        SearchResponse<Map> hiddenBatch = response(3L, 101L, 102L);
        SearchResponse<Map> publicBatch = response(3L, 103L);
        when(client.search(
                org.mockito.ArgumentMatchers.<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
                org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenReturn(hiddenBatch, publicBatch);

        var wiki = mock(com.platform.searchservice.content.WikiContentClient.class, invocation -> {
            if (!invocation.getMethod().getName().equals("filterVisiblePages")) {
                throw new UnsupportedOperationException(invocation.getMethod().getName());
            }
            Collection<Long> ids = invocation.getArgument(1);
            return ids.contains(103L) ? Set.of(103L) : Set.of();
        });
        OpenSearchQueryService search = new OpenSearchQueryService(
                client, scopedTo(AccessScope.of(Set.of(10L))), wiki);

        SearchResults result = search.search(1L, new SearchInput("검색", List.of(), List.of(), false, null, null, null, null, 0, 1));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.totalExact()).isTrue();
        assertThat(result.hits()).extracting(SearchHit::id).containsExactly("103");
        verify(client, times(2)).search(
                org.mockito.ArgumentMatchers.<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
                org.mockito.ArgumentMatchers.eq(Map.class));
    }

    @SuppressWarnings("unchecked")
    private static SearchResponse<Map> response(long total, long... pageIds) {
        SearchResponse<Map> response = mock(SearchResponse.class);
        HitsMetadata<Map> metadata = mock(HitsMetadata.class);
        TotalHits totalHits = mock(TotalHits.class);
        when(totalHits.value()).thenReturn(total);
        when(metadata.total()).thenReturn(totalHits);
        List<Hit<Map>> hits = java.util.Arrays.stream(pageIds).mapToObj(id -> {
            Hit<Map> hit = mock(Hit.class);
            when(hit.source()).thenReturn(Map.of(
                    "docType", "PAGE",
                    "pageId", id,
                    "spaceId", 10L,
                    "spaceKey", "dev",
                    "spaceName", "개발",
                    "title", "문서 " + id,
                    "updatedAt", 1_000L));
            when(hit.highlight()).thenReturn(Map.of());
            when(hit.score()).thenReturn(1.0);
            return hit;
        }).toList();
        when(metadata.hits()).thenReturn(hits);
        when(response.hits()).thenReturn(metadata);
        when(response.took()).thenReturn(1L);
        return response;
    }

    /** 후필터 무개입 스텁 — 이 테스트들은 OpenSearch 도달 여부만 본다. */
    private static com.platform.searchservice.content.WikiContentClient allVisible() {
        return org.mockito.Mockito.mock(com.platform.searchservice.content.WikiContentClient.class,
                inv -> {
                    if (inv.getMethod().getName().equals("filterVisiblePages")) {
                        return new java.util.HashSet<>((java.util.Collection<?>) inv.getArgument(1));
                    }
                    throw new UnsupportedOperationException(inv.getMethod().getName());
                });
    }

    /** 검색 경로는 전역 관리자 여부를 보지 않는다 — 그 판정은 재색인 관리 REST 몫이다. */
    private static PermissionClient scopedTo(AccessScope scope) {
        return new PermissionClient() {
            @Override
            public AccessScope accessibleSpaces(long userId) {
                return scope;
            }

            @Override
            public boolean isGlobalAdmin(long userId) {
                throw new UnsupportedOperationException("검색 경로는 전역 관리자 판정을 쓰지 않는다");
            }
        };
    }

    private static SearchInput input(List<String> spaceIds) {
        return new SearchInput("검색", spaceIds, List.of(), false, null, null, null, null, 0, 20);
    }
}
