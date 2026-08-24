package com.platform.searchservice.search;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.proto.org.v1.Grant;
import com.platform.proto.org.v1.ListUserGrantsRequest;
import com.platform.proto.org.v1.ListUserGrantsResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import com.platform.proto.org.v1.Role;
import com.platform.searchservice.config.GraphQlAccessLogInterceptor;
import com.platform.searchservice.config.GraphQlConfig;
import com.platform.searchservice.index.AttachmentDoc;
import com.platform.searchservice.index.IndexNames;
import com.platform.searchservice.index.OpenSearchIndexService;
import com.platform.searchservice.index.PageDoc;
import com.platform.searchservice.permission.GrpcPermissionClient;
import com.platform.searchservice.permission.PermissionClient;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GraphQL HTTP → in-process gRPC 권한 → 실제 nori OpenSearch 왕복 검증. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(GraphQlSearchIntegrationTest.TestGrpcConfig.class)
class GraphQlSearchIntegrationTest {

    private static final int OPENSEARCH_PORT = 9200;
    private static final long USER = 42L;
    private static final AtomicLong VERSION = new AtomicLong(100_000L);

    private static final String SEARCH_OPERATION = """
            query Search($input: SearchInput!) {
              search(input: $input) {
                total
                tookMs
                hits {
                  id
                  docType
                  spaceId
                  spaceKey
                  spaceName
                  pageId
                  title
                  filename
                  highlights
                  updatedAt
                  score
                }
              }
            }
            """;

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

    @DynamicPropertySource
    static void openSearchProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.opensearch.uri", () ->
                "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(OPENSEARCH_PORT));
        registry.add("platform.opensearch.bootstrap.enabled", () -> true);
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired OpenSearchClient client;
    @Autowired OpenSearchIndexService indexes;
    @Autowired TestPermissionService permissions;
    @Autowired TestWikiVisibility wikiVisibility;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        wikiVisibility.hidden.clear();
        client.deleteByQuery(d -> d
                .index(List.of(IndexNames.SEARCH_TARGETS))
                .query(q -> q.matchAll(m -> m))
                .refresh(true));
    }

    @Test
    void 한국어_본문이_nori로_검색되고_실제_응답값이_채워진다() throws Exception {
        permissions.allowSpaces(USER, 10L);
        indexPage(1001L, 10L, "온보딩", "개발환경설정 안내", "published");
        refresh();

        performSearch(USER, input("환경"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.tookMs").isNumber())
                .andExpect(jsonPath("$.data.search.hits[0].id").value("1001"))
                .andExpect(jsonPath("$.data.search.hits[0].score").isNumber());
        assertThat(permissions.calls()).isEqualTo(1);
    }

    @Test
    void 페이지_제한_후필터가_히트를_거르고_total을_보정한다() throws Exception {
        permissions.allowSpaces(USER, 10L);
        indexPage(1901L, 10L, "제한문서 검색어", "본문", "published");
        indexPage(1902L, 10L, "공개문서 검색어", "본문", "published");
        refresh();

        // wiki 판정이 1901을 숨긴다(W18 페이지 제한) — 히트·total 모두에서 사라져야 한다
        wikiVisibility.hidden.add(1901L);
        performSearch(USER, input("검색어"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits.length()").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].id").value("1902"));
    }

    @Test
    void 제목_히트가_본문_히트보다_위에_오고_하이라이트가_내려온다() throws Exception {
        permissions.allowSpaces(USER, 10L);
        indexPage(1101L, 10L, "검색가중치", "다른 내용", "published");
        indexPage(1102L, 10L, "다른 제목", "검색가중치", "published");
        refresh();

        performSearch(USER, input("검색가중치"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.hits[0].id").value("1101"))
                .andExpect(jsonPath("$.data.search.hits[0].highlights[0]", containsString("<em>")));
    }

    @Test
    void 첨부_filename과_docTypes_필터가_두_별칭_질의에서_동작한다() throws Exception {
        permissions.allowSpaces(USER, 10L);
        indexPage(1201L, 10L, "guide", "guide", "published");
        indexes.upsertAttachment(new AttachmentDoc(
                AttachmentDoc.DOC_TYPE, 1202L, 1201L, 10L, "dev", "개발",
                "guide-manual.pdf", "application/pdf", 100L, USER, 1_000L), nextVersion());
        refresh();

        performSearch(USER, input("guide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(2));

        Map<String, Object> input = input("guide");
        input.put("docTypes", List.of("ATTACHMENT"));
        performSearch(USER, input)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].docType").value("ATTACHMENT"))
                .andExpect(jsonPath("$.data.search.hits[0].filename").value("guide-manual.pdf"));
    }

    @Test
    void 접근_가능_스페이스만_남고_요청으로_권한을_넓힐_수_없다() throws Exception {
        permissions.allowSpaces(USER, 10L);
        indexPage(1301L, 10L, "권한검색", "본문", "published");
        indexPage(1302L, 20L, "권한검색", "본문", "published");
        refresh();

        performSearch(USER, input("권한검색"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].spaceId").value("10"));

        Map<String, Object> widened = input("권한검색");
        widened.put("spaceIds", List.of("20"));
        performSearch(USER, widened)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(0))
                .andExpect(jsonPath("$.data.search.hits.length()").value(0));
    }

    @Test
    void GLOBAL_grant는_전_스페이스를_검색한다() throws Exception {
        permissions.allowGlobal(USER);
        indexPage(1401L, 10L, "전역문서", "본문", "published");
        indexPage(1402L, 20L, "전역문서", "본문", "published");
        refresh();

        performSearch(USER, input("전역문서"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(2));
    }

    @Test
    void 접근_가능_스페이스가_없으면_빈_결과다() throws Exception {
        indexPage(1501L, 10L, "숨긴문서", "본문", "published");
        refresh();

        performSearch(USER, input("숨긴문서"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(0))
                .andExpect(jsonPath("$.data.search.tookMs").value(0))
                .andExpect(jsonPath("$.data.search.hits.length()").value(0));
    }

    @Test
    void includeDrafts_기본값은_초안을_거르고_true면_포함한다() throws Exception {
        permissions.allowSpaces(USER, 10L);
        indexPage(1601L, 10L, "초안검색", "본문", "published");
        indexPage(1602L, 10L, "초안검색", "본문", "draft");
        refresh();

        performSearch(USER, input("초안검색"))
                .andExpect(jsonPath("$.data.search.total").value(1));

        Map<String, Object> includeDrafts = input("초안검색");
        includeDrafts.put("includeDrafts", true);
        performSearch(USER, includeDrafts)
                .andExpect(jsonPath("$.data.search.total").value(2));
    }

    @Test
    void size는_100으로_잘리고_page는_0_base로_동작한다() throws Exception {
        permissions.allowSpaces(USER, 10L);
        for (int i = 0; i < 105; i++) {
            indexPage(17_000L + i, 10L, "페이지검색 " + i, "본문", "published");
        }
        refresh();

        Map<String, Object> oversized = input("페이지검색");
        oversized.put("size", 1_000);
        performSearch(USER, oversized)
                .andExpect(jsonPath("$.data.search.total").value(105))
                .andExpect(jsonPath("$.data.search.hits.length()").value(100));

        Map<String, Object> lastPage = input("페이지검색");
        lastPage.put("page", 10);
        lastPage.put("size", 10);
        performSearch(USER, lastPage)
                .andExpect(jsonPath("$.data.search.total").value(105))
                .andExpect(jsonPath("$.data.search.hits.length()").value(5));
    }

    @Test
    void 토큰_없이_graphql을_호출하면_401이다() throws Exception {
        mvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphQlBody(SEARCH_OPERATION, "Search", Map.of("input", input("검색")))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void org_service_불능은_빈_결과와_다른_SERVICE_UNAVAILABLE_오류다() throws Exception {
        permissions.fail(Status.UNAVAILABLE);

        performSearch(USER, input("검색"))
                // 실행된 GraphQL 요청은 200이고, 503 의미는 안정적인 extensions 계약으로 전달한다.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.errors[0].extensions.httpStatus").value(503));
    }

    @Test
    void 최대_깊이를_넘는_질의는_실행_전에_거부된다() throws Exception {
        String tooDeep = """
                query TooDeep {
                  __type(name: "SearchHit") {
                    fields { type { ofType { ofType { name } } } }
                  }
                }
                """;

        performGraphQl(USER, tooDeep, "TooDeep", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("maximum query depth exceeded")));
    }

    @Test
    void 최대_복잡도를_넘는_질의는_실행_전에_거부된다() throws Exception {
        // 실제 스키마 필드로 센다 — `__typename` 같은 메타 필드는 복잡도 계산에 잡히지 않아,
        // 그걸로 만든 질의는 상한을 넘겨도 통과해버린다(가드가 있는지 검증하지 못한다).
        String fields = IntStream.rangeClosed(0, GraphQlConfig.MAX_QUERY_COMPLEXITY)
                .mapToObj(i -> "f" + i + ": search(input: {query: \"x\"}) { total }")
                .collect(java.util.stream.Collectors.joining(" "));
        String tooComplex = "query TooComplex { " + fields + " }";

        performGraphQl(USER, tooComplex, "TooComplex", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].message", containsString("maximum query complexity exceeded")));
    }

    @Test
    void 접근_로그에_operation_name이_구조화_필드로_남는다() throws Exception {
        permissions.allowSpaces(USER, 10L);
        indexPage(1801L, 10L, "로그검색", "본문", "published");
        refresh();

        Logger logger = (Logger) LoggerFactory.getLogger(GraphQlAccessLogInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            performSearch(USER, input("로그검색")).andExpect(status().isOk());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).anySatisfy(event ->
                assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("operationName");
                    assertThat(pair.value).isEqualTo("Search");
                }));
    }

    private ResultActions performSearch(long userId, Map<String, Object> input) throws Exception {
        return performGraphQl(userId, SEARCH_OPERATION, "Search", Map.of("input", input));
    }

    private ResultActions performGraphQl(
            long userId,
            String query,
            String operationName,
            Map<String, Object> variables) throws Exception {
        return mvc.perform(post("/graphql")
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphQlBody(query, operationName, variables)));
    }

    private String graphQlBody(String query, String operationName, Map<String, Object> variables)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "query", query,
                "operationName", operationName,
                "variables", variables));
    }

    private void indexPage(long pageId, long spaceId, String title, String content, String status) {
        indexes.upsertPage(new PageDoc(
                PageDoc.DOC_TYPE, pageId, spaceId, "space-" + spaceId, "스페이스 " + spaceId,
                title, content, "page", status, 1, USER, 1_000L), nextVersion());
    }

    private static long nextVersion() {
        return VERSION.incrementAndGet();
    }

    private void refresh() throws IOException {
        client.indices().refresh(r -> r.index(List.of(IndexNames.SEARCH_TARGETS)));
    }

    private static Map<String, Object> input(String query) {
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        return input;
    }

    private static RequestPostProcessor asUser(long userId) {
        return jwt().jwt(j -> j
                        .subject(String.valueOf(userId))
                        .claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestGrpcConfig {
        private static final String SERVER_NAME = InProcessServerBuilder.generateName();

        @Bean
        TestPermissionService testPermissionService() {
            return new TestPermissionService();
        }

        @Bean(destroyMethod = "shutdownNow")
        Server testPermissionServer(TestPermissionService service) throws IOException {
            return InProcessServerBuilder.forName(SERVER_NAME)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start();
        }

        @Bean(destroyMethod = "shutdownNow")
        @Qualifier("testPermissionChannel")
        ManagedChannel testPermissionChannel(Server testPermissionServer) {
            return InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
        }

        @Bean
        @Primary
        PermissionClient testPermissionClient(
                @Qualifier("testPermissionChannel") ManagedChannel channel) {
            return new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel));
        }

        @Bean
        TestWikiVisibility testWikiVisibility() {
            return new TestWikiVisibility();
        }

        /** W18 후필터 경유 스텁 — hidden에 넣은 페이지만 걸러진다(기본 전부 보임). */
        @Bean
        @Primary
        com.platform.searchservice.content.WikiContentClient testWikiContent(TestWikiVisibility visibility) {
            return new com.platform.searchservice.content.WikiContentClient() {
                @Override
                public java.util.Optional<com.platform.proto.wiki.v1.PageContent> getPage(long pageId) {
                    throw new UnsupportedOperationException("검색 경로는 본문 조달을 쓰지 않는다");
                }

                @Override
                public java.util.Optional<com.platform.proto.wiki.v1.AttachmentMeta> getAttachment(long attachmentId) {
                    throw new UnsupportedOperationException("검색 경로는 첨부 조달을 쓰지 않는다");
                }

                @Override
                public void streamPages(long spaceId, java.util.function.Consumer<com.platform.proto.wiki.v1.PageContent> consumer) {
                    throw new UnsupportedOperationException("검색 경로는 백필을 쓰지 않는다");
                }

                @Override
                public void streamAttachments(long spaceId, java.util.function.Consumer<com.platform.proto.wiki.v1.AttachmentMeta> consumer) {
                    throw new UnsupportedOperationException("검색 경로는 백필을 쓰지 않는다");
                }

                @Override
                public java.util.Set<Long> filterVisiblePages(long userId, java.util.Collection<Long> pageIds) {
                    java.util.Set<Long> visible = new java.util.HashSet<>(pageIds);
                    visible.removeAll(visibility.hidden);
                    return visible;
                }
            };
        }
    }

    /** 테스트가 페이지 가시성을 조작하는 손잡이. */
    static class TestWikiVisibility {
        final java.util.Set<Long> hidden = java.util.concurrent.ConcurrentHashMap.newKeySet();
    }

    static class TestPermissionService extends PermissionServiceGrpc.PermissionServiceImplBase {
        private final Map<Long, List<Grant>> grantsByUser = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Status failure;

        void reset() {
            grantsByUser.clear();
            calls.set(0);
            failure = null;
        }

        void allowSpaces(long userId, long... spaceIds) {
            List<Grant> grants = new ArrayList<>();
            for (long spaceId : spaceIds) {
                grants.add(grant(ResourceType.SPACE, String.valueOf(spaceId)));
            }
            grantsByUser.put(userId, List.copyOf(grants));
        }

        void allowGlobal(long userId) {
            grantsByUser.put(userId, List.of(grant(ResourceType.GLOBAL, "")));
        }

        void fail(Status status) {
            failure = status;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public void listUserGrants(
                ListUserGrantsRequest request,
                StreamObserver<ListUserGrantsResponse> responseObserver) {
            calls.incrementAndGet();
            if (failure != null) {
                responseObserver.onError(failure.asRuntimeException());
                return;
            }
            responseObserver.onNext(ListUserGrantsResponse.newBuilder()
                    .addAllGrants(grantsByUser.getOrDefault(request.getUserId(), List.of()))
                    .build());
            responseObserver.onCompleted();
        }

        private static Grant grant(ResourceType type, String resourceId) {
            return Grant.newBuilder()
                    .setResourceType(type)
                    .setResourceId(resourceId)
                    .setRole(Role.VIEWER)
                    .build();
        }
    }
}
