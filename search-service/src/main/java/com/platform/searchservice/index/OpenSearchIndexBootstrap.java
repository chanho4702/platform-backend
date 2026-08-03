package com.platform.searchservice.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;

/** 최초 물리 인덱스와 코드가 사용하는 별칭을 기동 시 확보한다. */
@Component
@ConditionalOnProperty(value = "platform.opensearch.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OpenSearchIndexBootstrap implements ApplicationRunner {

    private static final String PAGE_MAPPING = "opensearch/wiki-page.json";
    private static final String ATTACHMENT_MAPPING = "opensearch/wiki-attachment.json";

    private final OpenSearchClient client;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            initialize();
        } catch (Exception e) {
            log.error("OpenSearch 연결 또는 색인 부트스트랩 실패 — 애플리케이션 기동 중단", e);
            // 색인이 없는 채로 살아 있으면 이후 소비자가 이벤트를 ACK해 원본 이벤트만 사라질 수 있다.
            // 08-02의 무음 장애를 반복하지 않도록 준비되지 않은 인스턴스는 트래픽을 받지 않게 한다.
            throw new IllegalStateException("OpenSearch 색인 준비에 실패했습니다", e);
        }
    }

    /** 통합 테스트와 재기동 복구가 같은 경로를 직접 실행할 수 있게 공개한다. */
    public void initialize() throws IOException {
        if (!client.ping().value()) {
            throw new IOException("OpenSearch ping 응답이 정상이 아닙니다");
        }
        ensureIndex(IndexNames.PAGE_INDEX_V1, IndexNames.PAGE_ALIAS, PAGE_MAPPING);
        ensureIndex(IndexNames.ATTACHMENT_INDEX_V1, IndexNames.ATTACHMENT_ALIAS, ATTACHMENT_MAPPING);
        log.info("OpenSearch 색인 준비 완료: aliases=[{}, {}]",
                IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS);
    }

    private void ensureIndex(String physicalIndex, String alias, String mappingResource) throws IOException {
        if (!client.indices().exists(e -> e.index(physicalIndex)).value()) {
            createIndex(physicalIndex, mappingResource);
        }

        // 재색인 중 별칭이 이미 v2를 가리킬 수 있으므로, 존재하는 별칭을 v1로 되돌리지 않는다.
        if (!client.indices().existsAlias(e -> e.name(alias)).value()) {
            client.indices().putAlias(p -> p
                    .index(physicalIndex)
                    .name(alias)
                    .isWriteIndex(true));
            log.info("OpenSearch 별칭 생성: alias={} index={}", alias, physicalIndex);
        }
    }

    private void createIndex(String physicalIndex, String mappingResource) throws IOException {
        try (InputStream mapping = new ClassPathResource(mappingResource).getInputStream()) {
            JsonNode definition = objectMapper.readTree(mapping);
            client.indices().create(c -> c
                    .index(physicalIndex)
                    // CreateIndex 자체는 JSON 로딩을 지원하지 않아 두 최상위 절만 나눈다.
                    // 분석기와 strict 매핑의 값은 여전히 resources JSON 한 곳에서만 읽는다.
                    .settings(s -> s.withJson(new StringReader(definition.required("settings").toString())))
                    .mappings(m -> m.withJson(new StringReader(definition.required("mappings").toString()))));
            log.info("OpenSearch 물리 인덱스 생성: index={} mapping={}", physicalIndex, mappingResource);
        } catch (OpenSearchException e) {
            // 여러 인스턴스가 동시에 뜨면 exists 이후 create 사이에서 다른 인스턴스가 먼저 만들 수 있다.
            if (e.status() != 400 || !"resource_already_exists_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }
}
