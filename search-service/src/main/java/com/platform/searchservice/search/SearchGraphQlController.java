package com.platform.searchservice.search;

import com.platform.searchservice.index.SearchIndexReadiness;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class SearchGraphQlController {

    private final OpenSearchQueryService search;
    private final SearchIndexReadiness readiness;

    @QueryMapping
    public SearchResults search(@Argument("input") SearchInput input, @AuthenticationPrincipal Jwt jwt) {
        // 색인이 아직 준비되지 않았으면 빈 결과가 아니라 장애를 알린다 — 빈 결과로 삼키면
        // "검색이 안 나온다"가 조용한 오답이 된다. 오류 계약은 org 불능과 같다(SERVICE_UNAVAILABLE/503).
        readiness.requireReady();
        // 플랫폼의 JWT sub는 숫자 userId다(wiki-backend의 SpaceController와 같은 경계 변환).
        long userId = Long.parseLong(jwt.getSubject());
        return search.search(userId, input);
    }
}
