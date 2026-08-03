package com.platform.searchservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * OpenSearch 클라이언트.
 *
 * `opensearch-java`를 쓴다 — Elasticsearch 8 클라이언트는 응답 헤더로 서버 종류를 검사해
 * OpenSearch 접속을 거부한다(둘은 API가 대체로 호환되지만 클라이언트는 서로 못 쓴다).
 *
 * 보안 플러그인은 꺼진 내부망 전용이라 자격증명을 붙이지 않는다(설계 §10) — 온프렘 배포판에서
 * 켤 때 여기에 인증을 추가한다.
 */
@Configuration
public class OpenSearchConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(OpenSearchTransport.class)
    OpenSearchTransport openSearchTransport(
            @Value("${platform.opensearch.uri}") String uri,
            ObjectMapper objectMapper) {
        URI parsed = URI.create(uri);
        HttpHost host = new HttpHost(parsed.getScheme(), parsed.getHost(), parsed.getPort());
        // Spring이 구성한 ObjectMapper를 재사용한다 — 날짜·null 처리 정책이 앱 전체와 갈리지 않게
        return ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper(objectMapper))
                .build();
    }

    @Bean
    OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }
}
