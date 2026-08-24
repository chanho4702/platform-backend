package com.platform.searchservice.reindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.proto.wiki.v1.PageStatus;
import com.platform.proto.wiki.v1.PageType;
import com.platform.searchservice.common.ConflictException;
import com.platform.searchservice.common.NotFoundException;
import com.platform.searchservice.common.ServiceUnavailableException;
import com.platform.searchservice.content.WikiContentClient;
import com.platform.searchservice.index.IndexNames;
import com.platform.searchservice.index.OpenSearchIndexBootstrap;
import com.platform.searchservice.index.OpenSearchIndexFactory;
import com.platform.searchservice.index.OpenSearchIndexService;
import com.platform.searchservice.index.PageDoc;
import org.apache.hc.core5.http.HttpHost;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 백필/재색인(T9)을 실제 OpenSearch에 대고 검증한다.
 *
 * 페이크 OpenSearch로는 이 기능의 핵심(별칭 원자 스위치, 실패 시 구 인덱스 유지)을 증명할 수 없다 —
 * 별칭 전환은 엔진의 원자성에 기대는 연산이라 실제 엔진 왕복으로만 확인된다.
 */
@Testcontainers
class ReindexServiceTest {

    private static final int OPENSEARCH_PORT = 9200;

    private static final ImageFromDockerfile OPENSEARCH_WITH_NORI =
            new ImageFromDockerfile("search-service-opensearch-nori:2.19.0", false)
                    .withDockerfileFromBuilder(builder -> builder
                            .from("opensearchproject/opensearch:2.19.0")
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
    static OpenSearchIndexFactory factory;
    static OpenSearchIndexBootstrap bootstrap;
    static OpenSearchIndexService indexes;

    private StubWikiContent content;

    @BeforeAll
    static void connect() {
        HttpHost host = new HttpHost("http", openSearch.getHost(), openSearch.getMappedPort(OPENSEARCH_PORT));
        transport = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper(new ObjectMapper().findAndRegisterModules()))
                .build();
        client = new OpenSearchClient(transport);
        factory = new OpenSearchIndexFactory(client, new ObjectMapper());
        bootstrap = new OpenSearchIndexBootstrap(factory);
        indexes = new OpenSearchIndexService(client);
    }

    @AfterAll
    static void closeClient() throws Exception {
        if (transport != null) transport.close();
    }

    @BeforeEach
    void freshCluster() throws Exception {
        // 별칭까지 함께 지워 매 테스트가 "v1 + 별칭" 상태에서 시작하게 한다.
        client.indices().delete(d -> d
                .index(IndexNames.PAGE_ALIAS + "-v*", IndexNames.ATTACHMENT_ALIAS + "-v*")
                .ignoreUnavailable(true));
        bootstrap.initialize();
        content = new StubWikiContent();
    }

    @Test
    void 재색인이_성공하면_두_별칭이_함께_새_인덱스를_가리킨다() throws Exception {
        indexes.upsertPage(page(1L, 10L, "구 인덱스 문서", "낡은 본문"), 1_000L);
        content.pages.add(pageContent(1L, 10L, "새 색인 문서", "새 본문", 2_000L));
        content.pages.add(pageContent(2L, 10L, "두 번째", "본문", 2_001L));
        content.attachments.add(attachmentMeta(9L, 1L, 10L, "manual.pdf", 2_002L));

        ReindexService service = service(directExecutor());
        ReindexJobView started = service.start();

        ReindexJobView finished = service.status(started.jobId());
        assertThat(finished.state()).isEqualTo(ReindexState.SUCCEEDED);
        assertThat(finished.aliasSwitched()).isTrue();
        assertThat(finished.pagesIndexed()).isEqualTo(2);
        assertThat(finished.attachmentsIndexed()).isEqualTo(1);
        assertThat(finished.pageIndex()).isEqualTo("wiki-page-v2");
        assertThat(finished.attachmentIndex()).isEqualTo("wiki-attachment-v2");

        assertThat(aliasTarget(IndexNames.PAGE_ALIAS)).isEqualTo("wiki-page-v2");
        assertThat(aliasTarget(IndexNames.ATTACHMENT_ALIAS)).isEqualTo("wiki-attachment-v2");
        // 구 인덱스는 남긴다 — 되돌릴 수 있어야 하고, 삭제는 운영자의 수동 조작이다(설계 §9).
        assertThat(indexExists("wiki-page-v1")).isTrue();

        // 별칭 뒤가 실제로 새 인덱스다: 구 인덱스에만 있던 본문은 더 이상 검색되지 않는다.
        assertThat(titlesUnderAlias()).containsExactlyInAnyOrder("새 색인 문서", "두 번째");
    }

    @Test
    void 재색인된_문서는_외부_버전이_유지돼_오래된_이벤트에_덮이지_않는다() throws Exception {
        content.pages.add(pageContent(1L, 10L, "재색인 제목", "본문", 5_000L));

        service(directExecutor()).start();

        // updatedAt(5_000)보다 오래된 이벤트는 재색인 결과를 이기지 못해야 한다.
        assertThat(indexes.upsertPage(page(1L, 10L, "낡은 이벤트", "낡음"), 4_000L))
                .isEqualTo(com.platform.searchservice.index.IndexingResult.VERSION_CONFLICT);
        assertThat(indexes.upsertPage(page(1L, 10L, "새 이벤트", "최신"), 6_000L))
                .isEqualTo(com.platform.searchservice.index.IndexingResult.APPLIED);
    }

    @Test
    void 페이지_백필이_실패하면_별칭은_구_인덱스에_남는다() throws Exception {
        indexes.upsertPage(page(1L, 10L, "구 인덱스 문서", "낡은 본문"), 1_000L);
        content.pageFailure = new ServiceUnavailableException("페이지 백필 스트림이 중단됐습니다");

        ReindexService service = service(directExecutor());
        ReindexJobView started = service.start();

        ReindexJobView finished = service.status(started.jobId());
        assertThat(finished.state()).isEqualTo(ReindexState.FAILED);
        assertThat(finished.aliasSwitched()).isFalse();
        assertThat(finished.failure()).contains("페이지 백필 스트림이 중단됐습니다");

        assertThat(aliasTarget(IndexNames.PAGE_ALIAS)).isEqualTo("wiki-page-v1");
        assertThat(aliasTarget(IndexNames.ATTACHMENT_ALIAS)).isEqualTo("wiki-attachment-v1");
        assertThat(titlesUnderAlias()).containsExactly("구 인덱스 문서");
    }

    @Test
    void 첨부_백필만_실패해도_페이지_별칭을_옮기지_않는다() throws Exception {
        indexes.upsertPage(page(1L, 10L, "구 인덱스 문서", "낡은 본문"), 1_000L);
        content.pages.add(pageContent(2L, 10L, "새 색인 문서", "새 본문", 2_000L));
        content.attachmentFailure = new ServiceUnavailableException("첨부 백필 스트림이 중단됐습니다");

        ReindexService service = service(directExecutor());
        ReindexJobView started = service.start();

        ReindexJobView finished = service.status(started.jobId());
        assertThat(finished.state()).isEqualTo(ReindexState.FAILED);
        assertThat(finished.aliasSwitched()).isFalse();

        // 부분 완료를 절대 노출하지 않는다 — 페이지 쪽만 성공했다고 별칭을 옮기면
        // 첨부 검색이 통째로 비어버리는 상태가 조용히 배포된다.
        assertThat(aliasTarget(IndexNames.PAGE_ALIAS)).isEqualTo("wiki-page-v1");
        assertThat(aliasTarget(IndexNames.ATTACHMENT_ALIAS)).isEqualTo("wiki-attachment-v1");
        assertThat(titlesUnderAlias()).containsExactly("구 인덱스 문서");
        // 실패한 잡이 만든 인덱스는 남지만 별칭이 없으므로 검색 대상이 아니다.
        assertThat(aliasesOf("wiki-page-v2")).isEmpty();
    }

    @Test
    void 연속_재색인은_버전을_계속_올린다() throws Exception {
        content.pages.add(pageContent(1L, 10L, "문서", "본문", 2_000L));
        ReindexService service = service(directExecutor());

        service.start();
        assertThat(aliasTarget(IndexNames.PAGE_ALIAS)).isEqualTo("wiki-page-v2");

        service.start();
        assertThat(aliasTarget(IndexNames.PAGE_ALIAS)).isEqualTo("wiki-page-v3");
        assertThat(aliasTarget(IndexNames.ATTACHMENT_ALIAS)).isEqualTo("wiki-attachment-v3");
    }

    @Test
    void 실행_중에는_두_번째_재색인을_거부한다() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        content.beforePages = () -> {
            entered.countDown();
            try {
                hold.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ReindexService service = service(executor);
            ReindexJobView running = service.start();
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(service::start)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(running.jobId());

            hold.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> service.status(running.jobId()).state() == ReindexState.SUCCEEDED);

            // 끝났으면 다시 받을 수 있어야 한다 — 가드가 잡을 영구히 붙잡고 있으면 안 된다.
            ReindexJobView second = service.start();
            assertThat(second.jobId()).isNotEqualTo(running.jobId());
            // 다음 테스트가 인덱스를 지운 뒤에 이 잡이 살아 있으면 세대 번호가 어긋난다 — 끝까지 기다린다.
            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> service.status(second.jobId()).state() != ReindexState.RUNNING);
        } finally {
            hold.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 실행기가_거부하면_유령_RUNNING_잡을_남기지_않고_다음_재색인도_막지_않는다() throws Exception {
        content.pages.add(pageContent(1L, 10L, "문서", "본문", 2_000L));
        SwitchableExecutor executor = new SwitchableExecutor();
        ReindexService service = service(executor);

        assertThatThrownBy(service::start).isInstanceOf(RejectedExecutionException.class);

        // 등록만 되고 돌지 않은 잡이 남으면 영원히 RUNNING인 채로 조회된다. 호출자는 예외로 끝나
        // jobId조차 못 받았으므로 남아 있어도 손댈 방법이 없다.
        assertThat(service.trackedJobCount()).isZero();

        // 가드도 함께 풀려야 한다 — 안 그러면 거부 한 번에 재색인이 영구히 막힌다.
        executor.reject = false;
        ReindexJobView accepted = service.start();
        assertThat(service.status(accepted.jobId()).state()).isEqualTo(ReindexState.SUCCEEDED);
        assertThat(service.trackedJobCount()).isEqualTo(1);
        assertThat(aliasTarget(IndexNames.PAGE_ALIAS)).isEqualTo("wiki-page-v2");
    }

    @Test
    void 모르는_jobId_조회는_NotFound다() {
        ReindexService service = service(directExecutor());

        assertThatThrownBy(() -> service.status("없는-잡"))
                .isInstanceOf(NotFoundException.class);
    }

    private ReindexService service(Executor executor) {
        return new ReindexService(content, indexes, factory, executor);
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }

    private static String aliasTarget(String alias) throws Exception {
        var result = client.indices().getAlias(a -> a.name(alias)).result();
        assertThat(result).hasSize(1);
        return result.keySet().iterator().next();
    }

    private static List<String> aliasesOf(String index) throws Exception {
        var result = client.indices().getAlias(a -> a.index(index)).result();
        return result.values().stream().flatMap(a -> a.aliases().keySet().stream()).toList();
    }

    private static boolean indexExists(String index) throws Exception {
        return client.indices().exists(e -> e.index(index)).value();
    }

    private static List<String> titlesUnderAlias() throws Exception {
        client.indices().refresh(r -> r.index(IndexNames.PAGE_ALIAS));
        return client.search(s -> s
                                .index(IndexNames.PAGE_ALIAS)
                                .query(q -> q.matchAll(m -> m))
                                .size(100),
                        PageDoc.class)
                .hits().hits().stream().map(hit -> hit.source().title()).toList();
    }

    private static PageDoc page(long pageId, long spaceId, String title, String content) {
        return new PageDoc(PageDoc.DOC_TYPE, pageId, spaceId, "old-key", "기존 스페이스",
                title, content, "page", "published", 1, 9L, 1_000L);
    }

    private static PageContent pageContent(
            long pageId, long spaceId, String title, String body, long updatedAt) {
        return PageContent.newBuilder()
                .setPageId(pageId)
                .setSpaceId(spaceId)
                .setSpaceKey("dev")
                .setSpaceName("개발")
                .setType(PageType.PAGE)
                .setStatus(PageStatus.PUBLISHED)
                .setTitle(title)
                .setContent(body)
                .setVersion(1)
                .setAuthorId(9L)
                .setUpdatedAt(updatedAt)
                .build();
    }

    private static AttachmentMeta attachmentMeta(
            long attachmentId, long pageId, long spaceId, String filename, long createdAt) {
        return AttachmentMeta.newBuilder()
                .setAttachmentId(attachmentId)
                .setPageId(pageId)
                .setSpaceId(spaceId)
                .setSpaceKey("dev")
                .setSpaceName("개발")
                .setFilename(filename)
                .setContentType("application/pdf")
                .setSizeBytes(100L)
                .setUploadedBy(9L)
                .setCreatedAt(createdAt)
                .build();
    }

    /** 스레드 풀 포화·종료 직후처럼 실행기가 잡을 거부하는 상황을 켜고 끌 수 있게 한다. */
    private static final class SwitchableExecutor implements Executor {
        volatile boolean reject = true;

        @Override
        public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("실행기가 잡을 거부했습니다");
            command.run();
        }
    }

    /** 백필 스트림만 흉내 낸다 — 단건 조달은 T9 경로에서 쓰지 않는다. */
    private static final class StubWikiContent implements WikiContentClient {
        final List<PageContent> pages = new ArrayList<>();
        final List<AttachmentMeta> attachments = new ArrayList<>();
        volatile RuntimeException pageFailure;
        volatile RuntimeException attachmentFailure;
        volatile Runnable beforePages = () -> {};

        @Override
        public java.util.Set<Long> filterVisiblePages(long userId, java.util.Collection<Long> pageIds) {
            throw new UnsupportedOperationException("백필 경로는 권한 필터를 쓰지 않는다");
        }

        @Override
        public Optional<PageContent> getPage(long pageId) {
            throw new UnsupportedOperationException("백필 경로는 단건 조달을 쓰지 않는다");
        }

        @Override
        public Optional<AttachmentMeta> getAttachment(long attachmentId) {
            throw new UnsupportedOperationException("백필 경로는 단건 조달을 쓰지 않는다");
        }

        @Override
        public void streamPages(long spaceId, Consumer<PageContent> consumer) {
            beforePages.run();
            pages.forEach(consumer);
            if (pageFailure != null) throw pageFailure;
        }

        @Override
        public void streamAttachments(long spaceId, Consumer<AttachmentMeta> consumer) {
            attachments.forEach(consumer);
            if (attachmentFailure != null) throw attachmentFailure;
        }
    }
}
