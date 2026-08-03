package com.platform.searchservice.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.indices.get_alias.IndexAliases;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 페이크 없이 실제 nori 포함 OpenSearch에 색인 경로 전체를 실행한다. */
@Testcontainers
class OpenSearchIndexServiceTest {

    private static final int OPENSEARCH_PORT = 9200;

    private static final ImageFromDockerfile OPENSEARCH_WITH_NORI =
            new ImageFromDockerfile("search-service-opensearch-nori:2.19.0", false)
                    .withDockerfileFromBuilder(builder -> builder
                            .from("opensearchproject/opensearch:2.19.0")
                            // 운영 이미지와 같은 플러그인을 테스트 이미지도 스스로 설치한다.
                            .run("/usr/share/opensearch/bin/opensearch-plugin install --batch analysis-nori")
                            .build());

    @Container
    static final GenericContainer<?> openSearch = new GenericContainer<>(OPENSEARCH_WITH_NORI)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(OPENSEARCH_PORT)
            .waitingFor(Wait.forHttp("/")
                    .forPort(OPENSEARCH_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static OpenSearchTransport transport;
    static OpenSearchClient client;
    static OpenSearchIndexBootstrap bootstrap;
    static OpenSearchIndexService indexes;

    @BeforeAll
    static void connectAndBootstrap() throws Exception {
        HttpHost host = new HttpHost("http", openSearch.getHost(), openSearch.getMappedPort(OPENSEARCH_PORT));
        transport = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper(new ObjectMapper().findAndRegisterModules()))
                .build();
        client = new OpenSearchClient(transport);
        bootstrap = new OpenSearchIndexBootstrap(client, new ObjectMapper());
        bootstrap.initialize();
        indexes = new OpenSearchIndexService(client);
    }

    @AfterAll
    static void closeClient() throws Exception {
        if (transport != null) transport.close();
    }

    @Test
    void 부트스트랩을_두_번_실행해도_인덱스와_별칭_상태가_같다() throws Exception {
        AliasState before = aliasState();

        bootstrap.initialize();

        assertThat(aliasState()).isEqualTo(before);
        assertThat(before.indices()).containsExactlyInAnyOrder(
                IndexNames.PAGE_INDEX_V1, IndexNames.ATTACHMENT_INDEX_V1);
        assertThat(before.aliases().get(IndexNames.PAGE_INDEX_V1)).containsExactly(IndexNames.PAGE_ALIAS);
        assertThat(before.aliases().get(IndexNames.ATTACHMENT_INDEX_V1))
                .containsExactly(IndexNames.ATTACHMENT_ALIAS);
    }

    @Test
    void 오래된_occurred_at은_최신_페이지를_덮거나_지우지_못한다() throws Exception {
        long id = 101L;
        PageDoc latest = page(id, 10L, "최신 제목", "최신 본문");
        PageDoc stale = page(id, 10L, "과거 제목", "과거 본문");

        assertThat(indexes.upsertPage(latest, 2_000L)).isEqualTo(IndexingResult.APPLIED);
        assertThat(indexes.upsertPage(stale, 1_000L)).isEqualTo(IndexingResult.VERSION_CONFLICT);
        assertThat(indexes.deletePage(id, 1_500L)).isEqualTo(IndexingResult.VERSION_CONFLICT);

        assertThat(getPage(id)).isEqualTo(latest);
    }

    @Test
    void 같은_이벤트를_두_번_적용해도_문서는_한_건이고_내용이_같다() throws Exception {
        long id = 102L;
        PageDoc document = page(id, 10L, "멱등 제목", "멱등 본문");

        assertThat(indexes.upsertPage(document, 3_000L)).isEqualTo(IndexingResult.APPLIED);
        assertThat(indexes.upsertPage(document, 3_000L)).isEqualTo(IndexingResult.VERSION_CONFLICT);
        refresh(IndexNames.PAGE_ALIAS);

        assertThat(findPagesById(id)).singleElement().isEqualTo(document);
    }

    @Test
    void page_id_delete_by_query는_그_페이지의_첨부만_지운다() throws Exception {
        indexes.upsertAttachment(attachment(201L, 20L, 11L, "target-a.txt"), 4_001L);
        indexes.upsertAttachment(attachment(202L, 20L, 11L, "target-b.txt"), 4_002L);
        indexes.upsertAttachment(attachment(203L, 21L, 11L, "keep.txt"), 4_003L);
        refresh(IndexNames.ATTACHMENT_ALIAS);

        assertThat(indexes.deleteAttachmentsByPageId(20L)).isEqualTo(2L);

        assertThat(getAttachment(201L)).isNull();
        assertThat(getAttachment(202L)).isNull();
        assertThat(getAttachment(203L)).isNotNull();
    }

    @Test
    void space_id_delete_by_query는_두_별칭에서_대상_스페이스만_지운다() throws Exception {
        indexes.upsertPage(page(301L, 31L, "삭제 페이지", "본문"), 5_001L);
        indexes.upsertAttachment(attachment(302L, 301L, 31L, "delete.txt"), 5_002L);
        indexes.upsertPage(page(303L, 32L, "유지 페이지", "본문"), 5_003L);
        indexes.upsertAttachment(attachment(304L, 303L, 32L, "keep.txt"), 5_004L);
        refresh(IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS);

        assertThat(indexes.deleteBySpaceId(31L)).isEqualTo(2L);

        assertThat(getPage(301L)).isNull();
        assertThat(getAttachment(302L)).isNull();
        assertThat(getPage(303L)).isNotNull();
        assertThat(getAttachment(304L)).isNotNull();
    }

    @Test
    void space_update_by_query는_두_별칭의_대상_문서_표시값만_고친다() throws Exception {
        indexes.upsertPage(page(401L, 41L, "대상", "본문"), 6_001L);
        indexes.upsertAttachment(attachment(402L, 401L, 41L, "target.txt"), 6_002L);
        indexes.upsertPage(page(403L, 42L, "비대상", "본문"), 6_003L);
        refresh(IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS);

        assertThat(indexes.updateSpace(41L, "새 스페이스", "new-key")).isEqualTo(2L);

        assertThat(getPage(401L)).extracting(PageDoc::spaceName, PageDoc::spaceKey)
                .containsExactly("새 스페이스", "new-key");
        assertThat(getAttachment(402L)).extracting(AttachmentDoc::spaceName, AttachmentDoc::spaceKey)
                .containsExactly("새 스페이스", "new-key");
        assertThat(getPage(403L)).extracting(PageDoc::spaceName, PageDoc::spaceKey)
                .containsExactly("기존 스페이스", "old-key");
    }

    @Test
    void nori가_복합명사를_분해해_환경으로_검색된다() throws Exception {
        long id = 501L;
        indexes.upsertPage(page(id, 51L, "온보딩", "개발환경설정"), 7_001L);
        refresh(IndexNames.PAGE_ALIAS);

        var response = client.search(s -> s
                        .index(IndexNames.PAGE_ALIAS)
                        .query(q -> q.match(m -> m.field("content").query(FieldValue.of("환경")))),
                PageDoc.class);

        assertThat(response.hits().hits())
                .extracting(hit -> hit.source().pageId())
                .contains(id);
    }

    private static AliasState aliasState() throws Exception {
        Map<String, IndexAliases> state = client.indices().getAlias(a -> a
                .name(IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS)).result();
        Map<String, Set<String>> aliases = state.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> Set.copyOf(entry.getValue().aliases().keySet()),
                (left, right) -> left,
                TreeMap::new));
        return new AliasState(Set.copyOf(state.keySet()), aliases);
    }

    private static PageDoc getPage(long id) throws Exception {
        var response = client.get(g -> g.index(IndexNames.PAGE_ALIAS).id(IndexNames.pageDocId(id)), PageDoc.class);
        return response.found() ? response.source() : null;
    }

    private static AttachmentDoc getAttachment(long id) throws Exception {
        var response = client.get(g -> g.index(IndexNames.ATTACHMENT_ALIAS)
                .id(IndexNames.attachmentDocId(id)), AttachmentDoc.class);
        return response.found() ? response.source() : null;
    }

    private static java.util.List<PageDoc> findPagesById(long id) throws Exception {
        return client.search(s -> s
                        .index(IndexNames.PAGE_ALIAS)
                        .query(q -> q.term(t -> t.field("pageId").value(FieldValue.of(id)))), PageDoc.class)
                .hits().hits().stream().map(hit -> hit.source()).toList();
    }

    private static void refresh(String... aliases) throws Exception {
        client.indices().refresh(r -> r.index(java.util.List.of(aliases)));
    }

    private static PageDoc page(long pageId, long spaceId, String title, String content) {
        return new PageDoc(PageDoc.DOC_TYPE, pageId, spaceId, "old-key", "기존 스페이스",
                title, content, "page", "published", 1, 9L, 1_000L);
    }

    private static AttachmentDoc attachment(long attachmentId, long pageId, long spaceId, String filename) {
        return new AttachmentDoc(AttachmentDoc.DOC_TYPE, attachmentId, pageId, spaceId,
                "old-key", "기존 스페이스", filename, "text/plain", 10L, 9L, 1_000L);
    }

    private record AliasState(Set<String> indices, Map<String, Set<String>> aliases) {}
}
