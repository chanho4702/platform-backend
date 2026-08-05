package com.platform.searchservice.search;

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

    @QueryMapping
    public SearchResults search(@Argument("input") SearchInput input, @AuthenticationPrincipal Jwt jwt) {
        // 플랫폼의 JWT sub는 숫자 userId다(wiki-backend의 SpaceController와 같은 경계 변환).
        long userId = Long.parseLong(jwt.getSubject());
        return search.search(userId, input);
    }
}
