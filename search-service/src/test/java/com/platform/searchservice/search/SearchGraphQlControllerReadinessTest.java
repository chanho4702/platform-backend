package com.platform.searchservice.search;

import com.platform.common.error.ServiceUnavailableException;
import com.platform.searchservice.index.SearchIndexReadiness;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 색인 준비 전 검색 요청의 계약.
 *
 * <p>빈 결과가 아니라 장애를 알려야 한다 — 빈 결과로 삼키면 "검색이 안 나온다"가 조용한
 * 오답이 되고, 색인이 준비되면 저절로 고쳐지는 상황임을 아무도 모른다.
 * {@code ServiceUnavailableException}은 org 불능과 같은 경로로 나간다:
 * REST는 503 {@code {"error": ...}}, GraphQL은 {@code extensions.code=SERVICE_UNAVAILABLE}
 * + {@code httpStatus=503}(GraphQlExceptionResolver).
 */
class SearchGraphQlControllerReadinessTest {

    @Test
    void 색인_준비_전_검색은_503_계약으로_거절한다() {
        OpenSearchQueryService search = mock(OpenSearchQueryService.class);
        SearchIndexReadiness readiness = new SearchIndexReadiness(true);
        SearchGraphQlController controller = new SearchGraphQlController(search, readiness);

        SearchInput input = new SearchInput(
                "문서", null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.search(input, null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("검색 색인 준비 중");

        // OpenSearch에 질의조차 하지 않는다 — 준비 전 질의는 무의미한 오류 로그만 남긴다.
        verifyNoInteractions(search);
    }
}
