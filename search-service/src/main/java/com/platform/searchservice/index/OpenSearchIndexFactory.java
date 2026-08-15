package com.platform.searchservice.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;

/**
 * 물리 인덱스를 매핑 정의로부터 만든다.
 *
 * 기동 부트스트랩(v1)과 재색인(v{n+1})이 **같은 매핑 파일**을 쓰게 하려고 한 곳에 모았다.
 * 둘이 갈리면 재색인 후에야 분석기·필드가 달라진 게 드러나는데, 그때는 이미 별칭이 옮겨간 뒤다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenSearchIndexFactory {

    public static final String PAGE_MAPPING = "opensearch/wiki-page.json";
    public static final String ATTACHMENT_MAPPING = "opensearch/wiki-attachment.json";
    public static final String ISSUE_MAPPING = "opensearch/alm-issue.json";

    private final OpenSearchClient client;
    private final ObjectMapper objectMapper;

    public OpenSearchClient client() {
        return client;
    }

    /** 매핑 리소스로 물리 인덱스를 만든다. 이미 있으면 아무것도 하지 않는다(멱등). */
    public void createIndex(String physicalIndex, String mappingResource) throws IOException {
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

    public boolean indexExists(String physicalIndex) throws IOException {
        return client.indices().exists(e -> e.index(physicalIndex)).value();
    }

    public boolean aliasExists(String alias) throws IOException {
        return client.indices().existsAlias(e -> e.name(alias)).value();
    }

    /** 매핑 리소스 이름은 별칭에 붙는다 — 호출부가 둘을 따로 들고 다니다 어긋나지 않게. */
    public static String mappingFor(String alias) {
        if (IndexNames.PAGE_ALIAS.equals(alias)) return PAGE_MAPPING;
        if (IndexNames.ATTACHMENT_ALIAS.equals(alias)) return ATTACHMENT_MAPPING;
        if (IndexNames.ISSUE_ALIAS.equals(alias)) return ISSUE_MAPPING;
        throw new IllegalArgumentException("매핑이 정의되지 않은 별칭입니다: " + alias);
    }
}
