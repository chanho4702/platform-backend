package com.platform.searchservice.config;

import com.platform.searchservice.common.ServiceUnavailableException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 검색 실행 중 발생한 의존 서비스 장애를 안정적인 GraphQL 오류 계약으로 바꾼다. */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (!(exception instanceof ServiceUnavailableException unavailable)) return null;

        /*
         * DataFetcher 실패는 GraphQL 전송 자체의 실패가 아니므로 HTTP 200을 유지한다. 대신 플랫폼의
         * REST 503 의미를 extensions에 그대로 싣는다. 클라이언트는 code로 빈 결과와 구분할 수 있고,
         * 이후 한 요청에 여러 필드가 생겨도 정상 필드의 부분 응답을 잃지 않는다.
         */
        return GraphqlErrorBuilder.newError(environment)
                .message(unavailable.getMessage())
                .extensions(Map.of(
                        "code", "SERVICE_UNAVAILABLE",
                        "httpStatus", 503))
                .build();
    }
}
