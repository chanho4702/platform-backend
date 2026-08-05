package com.platform.searchservice.config;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class GraphQlConfig {

    /** 정상 search 응답의 최대 깊이는 3이다. 여유 한 단계만 두고 introspection 중첩 폭주를 막는다. */
    public static final int MAX_QUERY_DEPTH = 4;
    public static final int MAX_QUERY_COMPLEXITY = 50;

    @Bean
    GraphQlSourceBuilderCustomizer queryLimits() {
        return builder -> builder.instrumentation(List.of(
                new MaxQueryDepthInstrumentation(MAX_QUERY_DEPTH),
                new MaxQueryComplexityInstrumentation(MAX_QUERY_COMPLEXITY)));
    }
}
