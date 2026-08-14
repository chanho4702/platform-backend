package com.platform.searchservice.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 최초 물리 인덱스와 코드가 사용하는 별칭을 기동 시 확보한다. */
@Component
@ConditionalOnProperty(value = "platform.opensearch.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OpenSearchIndexBootstrap implements ApplicationRunner {

    private final OpenSearchIndexFactory factory;

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
        if (!factory.client().ping().value()) {
            throw new IOException("OpenSearch ping 응답이 정상이 아닙니다");
        }
        ensureIndex(IndexNames.PAGE_INDEX_V1, IndexNames.PAGE_ALIAS, OpenSearchIndexFactory.PAGE_MAPPING);
        ensureIndex(IndexNames.ATTACHMENT_INDEX_V1, IndexNames.ATTACHMENT_ALIAS,
                OpenSearchIndexFactory.ATTACHMENT_MAPPING);
        log.info("OpenSearch 색인 준비 완료: aliases=[{}, {}]",
                IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS);
    }

    private void ensureIndex(String physicalIndex, String alias, String mappingResource) throws IOException {
        if (!factory.indexExists(physicalIndex)) {
            factory.createIndex(physicalIndex, mappingResource);
        }

        // 재색인 중 별칭이 이미 v2를 가리킬 수 있으므로, 존재하는 별칭을 v1로 되돌리지 않는다.
        if (!factory.aliasExists(alias)) {
            factory.client().indices().putAlias(p -> p
                    .index(physicalIndex)
                    .name(alias)
                    .isWriteIndex(true));
            log.info("OpenSearch 별칭 생성: alias={} index={}", alias, physicalIndex);
        }
    }
}
