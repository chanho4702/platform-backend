package com.platform.searchservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class GraphQlAccessLogInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        String suppliedName = request.getOperationName();
        String operationName = suppliedName == null || suppliedName.isBlank() ? "anonymous" : suppliedName;
        long startedAt = System.nanoTime();

        return chain.next(request).doOnNext(response -> {
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            // addKeyValue 값은 docker 프로필의 ECS stdout JSON에서 최상위 검색 필드가 된다.
            log.atInfo()
                    .addKeyValue("event", "graphql.request")
                    .addKeyValue("operationName", operationName)
                    .addKeyValue("errors", response.getErrors().size())
                    .addKeyValue("latencyMs", latencyMs)
                    .log("GraphQL {} -> errors={} {}ms", operationName, response.getErrors().size(), latencyMs);
        });
    }
}
