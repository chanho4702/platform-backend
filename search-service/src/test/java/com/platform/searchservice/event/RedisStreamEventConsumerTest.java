package com.platform.searchservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.proto.events.v1.AttachmentAdded;
import com.platform.proto.events.v1.AttachmentDeleted;
import com.platform.proto.events.v1.EventEnvelope;
import com.platform.proto.events.v1.PageCreated;
import com.platform.proto.events.v1.PageDeleted;
import com.platform.proto.events.v1.PageUpdated;
import com.platform.proto.events.v1.SpaceCreated;
import com.platform.proto.events.v1.SpaceDeleted;
import com.platform.proto.events.v1.SpaceUpdated;
import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.GetAttachmentMetaRequest;
import com.platform.proto.wiki.v1.GetPageContentRequest;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.proto.wiki.v1.PageStatus;
import com.platform.proto.wiki.v1.PageType;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
import com.platform.searchservice.content.GrpcWikiContentClient;
import com.platform.searchservice.index.AttachmentDoc;
import com.platform.searchservice.index.IndexNames;
import com.platform.searchservice.index.IndexingResult;
import com.platform.searchservice.index.OpenSearchIndexBootstrap;
import com.platform.searchservice.index.OpenSearchIndexService;
import com.platform.searchservice.index.PageDoc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XPendingOptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 발행자와 같은 raw XADD부터 gRPC 조달, OpenSearch 반영, XACK/DLQ까지 실제 경계를 왕복한다.
 * Redis나 OpenSearch를 페이크로 바꾸면 직렬화·consumer group·외부 버전 경로가 빠지므로 금지한다.
 */
@Testcontainers
class RedisStreamEventConsumerTest {

    private static final int REDIS_PORT = 6379;
    private static final int OPENSEARCH_PORT = 9200;
    private static final AtomicLong IDS = new AtomicLong(10_000L);

    private static final ImageFromDockerfile OPENSEARCH_WITH_NORI =
            new ImageFromDockerfile("search-service-opensearch-nori:2.19.0", false)
                    .withDockerfileFromBuilder(builder -> builder
                            .from("opensearchproject/opensearch:2.19.0")
                            .run("/usr/share/opensearch/bin/opensearch-plugin install --batch analysis-nori")
                            .build());

    @Container
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    @Container
    static final GenericContainer<?> openSearchContainer = new GenericContainer<>(OPENSEARCH_WITH_NORI)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(OPENSEARCH_PORT)
            .waitingFor(Wait.forHttp("/")
                    .forPort(OPENSEARCH_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static LettuceConnectionFactory redisFactory;
    static StringRedisTemplate redis;
    static OpenSearchTransport transport;
    static OpenSearchClient openSearch;
    static OpenSearchIndexService indexes;

    String stream;
    String group;
    String dlq;
    FakeWikiContentService wikiService;
    Server grpcServer;
    ManagedChannel grpcChannel;
    RedisStreamEventConsumer consumer;

    @BeforeAll
    static void connectContainers() throws Exception {
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(
                redisContainer.getHost(), redisContainer.getMappedPort(REDIS_PORT));
        redisFactory = new LettuceConnectionFactory(redisConfiguration);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();

        HttpHost host = new HttpHost(
                "http", openSearchContainer.getHost(), openSearchContainer.getMappedPort(OPENSEARCH_PORT));
        transport = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper(new ObjectMapper().findAndRegisterModules()))
                .build();
        openSearch = new OpenSearchClient(transport);
        new OpenSearchIndexBootstrap(openSearch, new ObjectMapper()).initialize();
        indexes = new OpenSearchIndexService(openSearch);
    }

    @AfterAll
    static void closeContainers() throws Exception {
        if (redisFactory != null) redisFactory.destroy();
        if (transport != null) transport.close();
    }

    @BeforeEach
    void setUpGrpcAndStreamNames() throws Exception {
        stream = "platform:events:v1:test:" + UUID.randomUUID();
        group = "search-service";
        dlq = stream + ":dlq";

        wikiService = new FakeWikiContentService();
        String serverName = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(wikiService)
                .build()
                .start();
        grpcChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    @AfterEach
    void stopConsumerAndGrpc() {
        if (consumer != null) consumer.stop();
        if (grpcChannel != null) grpcChannel.shutdownNow();
        if (grpcServer != null) grpcServer.shutdownNow();
    }

    @Test
    void 발행자와_같은_proto_XADD가_페이지를_색인하고_ACK한다() throws Exception {
        long pageId = nextId();
        long occurredAt = version();
        PageContent supplied = pageContent(pageId, 101L, "첫 페이지", "왕복 본문");
        wikiService.pages.put(pageId, supplied);

        startConsumer(5, Duration.ofMillis(100));
        // 소비자 시작 시 스트림이 없어도 MKSTREAM으로 그룹까지 만들어져야 한다.
        awaitGroup();

        EventEnvelope event = envelope(occurredAt)
                .setPageCreated(PageCreated.newBuilder()
                        .setPageId(pageId).setSpaceId(101L).setTitle("첫 페이지"))
                .build();
        RecordId recordId = publishLikeWikiBackend(event);

        awaitAcknowledged(recordId);
        assertThat(getPage(pageId)).isEqualTo(pageDoc(supplied));
        assertThat(rawPayload(stream)).isEqualTo(event.toByteArray());
    }

    @Test
    void PageDeleted는_페이지와_그_페이지의_첨부를_함께_지운다() throws Exception {
        long pageId = nextId();
        long keepPageId = nextId();
        long attachmentA = nextId();
        long attachmentB = nextId();
        long keepAttachment = nextId();
        long occurredAt = version();

        assertThat(indexes.upsertPage(pageDoc(pageContent(pageId, 201L, "삭제", "본문")), occurredAt - 20))
                .isEqualTo(IndexingResult.APPLIED);
        indexes.upsertAttachment(attachmentDoc(attachmentA, pageId, 201L, "a.txt"), occurredAt - 19);
        indexes.upsertAttachment(attachmentDoc(attachmentB, pageId, 201L, "b.txt"), occurredAt - 18);
        indexes.upsertAttachment(attachmentDoc(keepAttachment, keepPageId, 201L, "keep.txt"), occurredAt - 17);
        refresh(IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS);

        startConsumer(5, Duration.ofMillis(100));
        RecordId recordId = publishLikeWikiBackend(envelope(occurredAt)
                .setPageDeleted(PageDeleted.newBuilder().setPageId(pageId).setSpaceId(201L))
                .build());

        awaitAcknowledged(recordId);
        assertThat(getPage(pageId)).isNull();
        assertThat(getAttachment(attachmentA)).isNull();
        assertThat(getAttachment(attachmentB)).isNull();
        assertThat(getAttachment(keepAttachment)).isNotNull();
    }

    @Test
    void SpaceUpdated는_두_인덱스의_표시값을_갱신한다() throws Exception {
        long pageId = nextId();
        long attachmentId = nextId();
        long spaceId = nextId();
        long occurredAt = version();
        indexes.upsertPage(pageDoc(pageContent(pageId, spaceId, "대상", "본문")), occurredAt - 10);
        indexes.upsertAttachment(attachmentDoc(attachmentId, pageId, spaceId, "target.txt"), occurredAt - 9);
        refresh(IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS);

        startConsumer(5, Duration.ofMillis(100));
        RecordId recordId = publishLikeWikiBackend(envelope(occurredAt)
                .setSpaceUpdated(SpaceUpdated.newBuilder()
                        .setSpaceId(spaceId).setName("새 스페이스").setKey("new-key"))
                .build());

        awaitAcknowledged(recordId);
        assertThat(getPage(pageId)).extracting(PageDoc::spaceName, PageDoc::spaceKey)
                .containsExactly("새 스페이스", "new-key");
        assertThat(getAttachment(attachmentId)).extracting(AttachmentDoc::spaceName, AttachmentDoc::spaceKey)
                .containsExactly("새 스페이스", "new-key");
    }

    @Test
    void 페이지_조달_NOT_FOUND는_삭제로_수렴하고_ACK한다() throws Exception {
        long pageId = nextId();
        long occurredAt = version();
        indexes.upsertPage(pageDoc(pageContent(pageId, 301L, "과거", "남아 있으면 안 됨")), occurredAt - 10);

        startConsumer(5, Duration.ofMillis(100));
        RecordId recordId = publishLikeWikiBackend(envelope(occurredAt)
                .setPageUpdated(PageUpdated.newBuilder()
                        .setPageId(pageId).setSpaceId(301L).setTitle("이미 삭제"))
                .build());

        awaitAcknowledged(recordId);
        assertThat(getPage(pageId)).isNull();
    }

    @Test
    void 페이지_조달_FAILED_PRECONDITION은_삭제하지_않고_pending에서_재시도한다() throws Exception {
        long pageId = nextId();
        long occurredAt = version();
        PageDoc existing = pageDoc(pageContent(pageId, 401L, "보존", "원본 데이터 수리 대상"));
        indexes.upsertPage(existing, occurredAt - 10);
        wikiService.pageFailures.put(pageId, Status.FAILED_PRECONDITION);

        startConsumer(100, Duration.ofMillis(250));
        publishLikeWikiBackend(envelope(occurredAt)
                .setPageUpdated(PageUpdated.newBuilder()
                        .setPageId(pageId).setSpaceId(401L).setTitle("고아 페이지"))
                .build());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            PendingMessages pending = pending();
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getTotalDeliveryCount()).isGreaterThanOrEqualTo(2L);
        });
        assertThat(getPage(pageId)).isEqualTo(existing);
        assertThat(streamLength(dlq)).isZero();
    }

    @Test
    void 재시도_상한을_넘으면_DLQ에_원본_proto를_옮기고_원본을_ACK한다() throws Exception {
        long pageId = nextId();
        long occurredAt = version();
        wikiService.pageFailures.put(pageId, Status.FAILED_PRECONDITION);
        EventEnvelope event = envelope(occurredAt)
                .setPageUpdated(PageUpdated.newBuilder()
                        .setPageId(pageId).setSpaceId(501L).setTitle("계속 실패"))
                .build();

        // 첫 프로세스는 최초 실패를 PEL에 남기고 종료한다.
        startConsumer(1, Duration.ofSeconds(10));
        RecordId recordId = publishLikeWikiBackend(event);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(pending()).hasSize(1);
            assertThat(pending().get(0).getTotalDeliveryCount()).isEqualTo(1L);
        });
        consumer.stop();

        // 새 consumer name으로 재기동해도 PEL delivery count가 유지되고 XCLAIM으로 회수돼야 한다.
        startConsumer(1, Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(streamLength(dlq)).isEqualTo(1L);
            assertThat(pending()).isEmpty();
        });
        assertThat(rawPayload(dlq)).isEqualTo(event.toByteArray());
        assertThat(lastDeliveredId()).isEqualTo(recordId.getValue());
    }

    @Test
    void 과거_PageDeleted_VERSION_CONFLICT는_ACK하고_최신_페이지와_첨부를_보존한다() throws Exception {
        long pageId = nextId();
        long attachmentId = nextId();
        long occurredAt = version();
        PageDoc latest = pageDoc(pageContent(pageId, 601L, "최신", "최신 본문"));
        AttachmentDoc attachment = attachmentDoc(attachmentId, pageId, 601L, "latest.txt");
        indexes.upsertPage(latest, occurredAt + 100);
        indexes.upsertAttachment(attachment, occurredAt + 101);
        refresh(IndexNames.ATTACHMENT_ALIAS);

        startConsumer(5, Duration.ofMillis(100));
        RecordId recordId = publishLikeWikiBackend(envelope(occurredAt)
                .setPageDeleted(PageDeleted.newBuilder().setPageId(pageId).setSpaceId(601L))
                .build());

        awaitAcknowledged(recordId);
        assertThat(getPage(pageId)).isEqualTo(latest);
        assertThat(getAttachment(attachmentId)).isEqualTo(attachment);
    }

    @Test
    void AttachmentAdded와_AttachmentDeleted는_단건_조달_upsert와_삭제를_수행한다() throws Exception {
        long pageId = nextId();
        long attachmentId = nextId();
        long occurredAt = version();
        AttachmentMeta supplied = attachmentMeta(attachmentId, pageId, 701L, "guide.pdf");
        wikiService.attachments.put(attachmentId, supplied);
        startConsumer(5, Duration.ofMillis(100));

        RecordId added = publishLikeWikiBackend(envelope(occurredAt)
                .setAttachmentAdded(AttachmentAdded.newBuilder()
                        .setAttachmentId(attachmentId).setPageId(pageId)
                        .setSpaceId(701L).setFilename("guide.pdf"))
                .build());
        awaitAcknowledged(added);
        assertThat(getAttachment(attachmentId)).isEqualTo(attachmentDoc(supplied));

        RecordId deleted = publishLikeWikiBackend(envelope(occurredAt + 1)
                .setAttachmentDeleted(AttachmentDeleted.newBuilder()
                        .setAttachmentId(attachmentId).setPageId(pageId).setSpaceId(701L))
                .build());
        awaitAcknowledged(deleted);
        assertThat(getAttachment(attachmentId)).isNull();
    }

    @Test
    void SpaceCreated는_무동작_ACK하고_SpaceDeleted는_두_인덱스를_지운다() throws Exception {
        long pageId = nextId();
        long attachmentId = nextId();
        long spaceId = nextId();
        long occurredAt = version();
        PageDoc page = pageDoc(pageContent(pageId, spaceId, "스페이스", "본문"));
        AttachmentDoc attachment = attachmentDoc(attachmentId, pageId, spaceId, "space.txt");
        indexes.upsertPage(page, occurredAt - 10);
        indexes.upsertAttachment(attachment, occurredAt - 9);
        refresh(IndexNames.PAGE_ALIAS, IndexNames.ATTACHMENT_ALIAS);
        startConsumer(5, Duration.ofMillis(100));

        RecordId created = publishLikeWikiBackend(envelope(occurredAt)
                .setSpaceCreated(SpaceCreated.newBuilder()
                        .setSpaceId(spaceId).setKey("old-key").setName("기존 스페이스"))
                .build());
        awaitAcknowledged(created);
        assertThat(getPage(pageId)).isEqualTo(page);
        assertThat(getAttachment(attachmentId)).isEqualTo(attachment);

        RecordId deleted = publishLikeWikiBackend(envelope(occurredAt + 1)
                .setSpaceDeleted(SpaceDeleted.newBuilder().setSpaceId(spaceId))
                .build());
        awaitAcknowledged(deleted);
        assertThat(getPage(pageId)).isNull();
        assertThat(getAttachment(attachmentId)).isNull();
    }

    private void startConsumer(int maxRetries, Duration retryIdle) {
        EventConsumerProperties properties = new EventConsumerProperties();
        properties.setStream(stream);
        properties.setConsumerGroup(group);
        properties.setMaxRetries(maxRetries);
        properties.setDlq(dlq);
        consumer = new RedisStreamEventConsumer(
                redis,
                new WikiEventIndexer(
                        new GrpcWikiContentClient(WikiContentServiceGrpc.newBlockingStub(grpcChannel)),
                        indexes),
                properties,
                "search-service-test",
                retryIdle,
                Duration.ofMillis(50));
        consumer.start();
        awaitGroup();
    }

    private void awaitGroup() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(groupInfoWhenReady().groupName()).isEqualTo(group));
    }

    private RecordId publishLikeWikiBackend(EventEnvelope event) {
        byte[] streamKey = bytes(stream);
        Map<byte[], byte[]> fields = Map.of(bytes("payload"), event.toByteArray());
        return redis.execute((RedisCallback<RecordId>) connection ->
                connection.streamCommands().xAdd(
                        MapRecord.create(streamKey, fields),
                        XAddOptions.maxlen(100_000).approximateTrimming(true)));
    }

    private void awaitAcknowledged(RecordId recordId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            XInfoGroup info = groupInfoWhenReady();
            assertThat(info.lastDeliveredId()).isEqualTo(recordId.getValue());
            assertThat(info.pendingCount()).isZero();
        });
    }

    /**
     * 소비자가 그룹을 만들기 전에는 XINFO GROUPS가 "no such key"로 터진다.
     * Awaitility의 untilAsserted는 AssertionError만 재시도하므로, "아직 없음"을 단언 실패로
     * 바꿔 기다리게 한다 — 이걸 안 하면 소비자 기동과 경합해 테스트가 간헐적으로 깨진다.
     */
    private XInfoGroup groupInfoWhenReady() {
        try {
            return groupInfo();
        } catch (RuntimeException notReady) {
            throw new AssertionError("consumer group이 아직 없다: " + group, notReady);
        }
    }

    private XInfoGroup groupInfo() {
        return redis.execute((RedisCallback<XInfoGroup>) connection ->
                connection.streamCommands().xInfoGroups(bytes(stream)).stream()
                        .filter(info -> group.equals(info.groupName()))
                        .findFirst()
                        .orElseThrow());
    }

    private String lastDeliveredId() {
        return groupInfo().lastDeliveredId();
    }

    private PendingMessages pending() {
        return redis.execute((RedisCallback<PendingMessages>) connection ->
                connection.streamCommands().xPending(
                        bytes(stream), group, XPendingOptions.unbounded(10L)));
    }

    private static Long streamLength(String key) {
        return redis.execute((RedisCallback<Long>) connection ->
                connection.streamCommands().xLen(bytes(key)));
    }

    private static byte[] rawPayload(String key) {
        List<ByteRecord> records = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                connection.streamCommands().xRange(bytes(key), Range.unbounded()));
        assertThat(records).isNotEmpty();
        return records.get(records.size() - 1).getValue().entrySet().stream()
                .filter(entry -> Arrays.equals(bytes("payload"), entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static PageDoc getPage(long pageId) throws Exception {
        var response = openSearch.get(
                request -> request.index(IndexNames.PAGE_ALIAS).id(IndexNames.pageDocId(pageId)),
                PageDoc.class);
        return response.found() ? response.source() : null;
    }

    private static AttachmentDoc getAttachment(long attachmentId) throws Exception {
        var response = openSearch.get(
                request -> request.index(IndexNames.ATTACHMENT_ALIAS)
                        .id(IndexNames.attachmentDocId(attachmentId)),
                AttachmentDoc.class);
        return response.found() ? response.source() : null;
    }

    private static void refresh(String... aliases) throws Exception {
        openSearch.indices().refresh(request -> request.index(List.of(aliases)));
    }

    private static EventEnvelope.Builder envelope(long occurredAt) {
        return EventEnvelope.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(occurredAt)
                .setActorId(1L)
                .setSource("wiki-backend");
    }

    private static PageContent pageContent(long pageId, long spaceId, String title, String content) {
        return PageContent.newBuilder()
                .setPageId(pageId)
                .setSpaceId(spaceId)
                .setSpaceKey("old-key")
                .setSpaceName("기존 스페이스")
                .setType(PageType.PAGE)
                .setStatus(PageStatus.PUBLISHED)
                .setTitle(title)
                .setContent(content)
                .setVersion(3)
                .setAuthorId(9L)
                .setUpdatedAt(1_700_000_000_000L)
                .build();
    }

    private static PageDoc pageDoc(PageContent page) {
        return new PageDoc(
                PageDoc.DOC_TYPE,
                page.getPageId(),
                page.getSpaceId(),
                page.getSpaceKey(),
                page.getSpaceName(),
                page.getTitle(),
                page.getContent(),
                "page",
                "published",
                page.getVersion(),
                page.getAuthorId(),
                page.getUpdatedAt());
    }

    private static AttachmentMeta attachmentMeta(
            long attachmentId, long pageId, long spaceId, String filename) {
        return AttachmentMeta.newBuilder()
                .setAttachmentId(attachmentId)
                .setPageId(pageId)
                .setSpaceId(spaceId)
                .setSpaceKey("old-key")
                .setSpaceName("기존 스페이스")
                .setFilename(filename)
                .setContentType("application/octet-stream")
                .setSizeBytes(42L)
                .setUploadedBy(9L)
                .setCreatedAt(1_700_000_000_000L)
                .build();
    }

    private static AttachmentDoc attachmentDoc(AttachmentMeta attachment) {
        return new AttachmentDoc(
                AttachmentDoc.DOC_TYPE,
                attachment.getAttachmentId(),
                attachment.getPageId(),
                attachment.getSpaceId(),
                attachment.getSpaceKey(),
                attachment.getSpaceName(),
                attachment.getFilename(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getUploadedBy(),
                attachment.getCreatedAt());
    }

    private static AttachmentDoc attachmentDoc(
            long attachmentId, long pageId, long spaceId, String filename) {
        return attachmentDoc(attachmentMeta(attachmentId, pageId, spaceId, filename));
    }

    private static long nextId() {
        return IDS.incrementAndGet();
    }

    private static long version() {
        return System.currentTimeMillis();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeWikiContentService
            extends WikiContentServiceGrpc.WikiContentServiceImplBase {

        final Map<Long, PageContent> pages = new ConcurrentHashMap<>();
        final Map<Long, AttachmentMeta> attachments = new ConcurrentHashMap<>();
        final Map<Long, Status> pageFailures = new ConcurrentHashMap<>();
        final Map<Long, Status> attachmentFailures = new ConcurrentHashMap<>();

        @Override
        public void getPageContent(
                GetPageContentRequest request, StreamObserver<PageContent> observer) {
            Status failure = pageFailures.get(request.getPageId());
            if (failure != null) {
                observer.onError(failure.asRuntimeException());
                return;
            }
            PageContent page = pages.get(request.getPageId());
            if (page == null) {
                observer.onError(Status.NOT_FOUND.asRuntimeException());
                return;
            }
            observer.onNext(page);
            observer.onCompleted();
        }

        @Override
        public void getAttachmentMeta(
                GetAttachmentMetaRequest request, StreamObserver<AttachmentMeta> observer) {
            Status failure = attachmentFailures.get(request.getAttachmentId());
            if (failure != null) {
                observer.onError(failure.asRuntimeException());
                return;
            }
            AttachmentMeta attachment = attachments.get(request.getAttachmentId());
            if (attachment == null) {
                observer.onError(Status.NOT_FOUND.asRuntimeException());
                return;
            }
            observer.onNext(attachment);
            observer.onCompleted();
        }
    }
}
