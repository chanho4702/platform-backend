package com.platform.searchservice.search;

import com.platform.searchservice.permission.AccessScope;
import com.platform.searchservice.permission.PermissionClient;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OpenSearchQueryServiceTest {

    @Test
    void 접근_가능한_스페이스가_없으면_OpenSearch를_호출하지_않는다() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        PermissionClient permissions = userId -> AccessScope.of(Set.of());
        OpenSearchQueryService search = new OpenSearchQueryService(client, permissions);

        SearchResults result = search.search(1L, input(List.of()));

        assertThat(result).isEqualTo(SearchResults.empty());
        verifyNoInteractions(client);
    }

    @Test
    void 요청_spaceIds와_권한의_교집합이_없어도_OpenSearch를_호출하지_않는다() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        PermissionClient permissions = userId -> AccessScope.of(Set.of(10L));
        OpenSearchQueryService search = new OpenSearchQueryService(client, permissions);

        SearchResults result = search.search(1L, input(List.of("999")));

        assertThat(result).isEqualTo(SearchResults.empty());
        verifyNoInteractions(client);
    }

    private static SearchInput input(List<String> spaceIds) {
        return new SearchInput("검색", spaceIds, List.of(), false, 0, 20);
    }
}
