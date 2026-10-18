package com.platform.searchservice.content;

import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.wiki.v1.FilterVisiblePagesRequest;
import com.platform.proto.wiki.v1.FilterVisiblePagesResponse;
import com.platform.proto.wiki.v1.GetPageContentRequest;
import com.platform.proto.wiki.v1.ListPageContentsRequest;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** P-04(데드라인)·P-18(503 문구에 내부 정보 없음) — 실제 blocking stub 왕복. */
class GrpcWikiContentClientTest {

    private final StubWiki wiki = new StubWiki();
    private Server server;
    private ManagedChannel channel;
    private GrpcWikiContentClient client;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(wiki).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = new GrpcWikiContentClient(WikiContentServiceGrpc.newBlockingStub(channel), 1, 1);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    void 위키가_응답하지_않으면_단건_조달은_데드라인_안에_503이다() {
        wiki.hang = true;
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> client.getPage(7L))
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessage("위키 콘텐츠를 가져오지 못했습니다"));
    }

    @Test
    void 위키가_응답하지_않으면_검색_후필터도_데드라인_안에_503이다() {
        wiki.hang = true;
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> client.filterVisiblePages(3L, List.of(1L, 2L)))
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessage("위키 서비스에 연결할 수 없습니다"));
    }

    @Test
    void 백필_스트림이_끝나지_않으면_스트림_데드라인에서_끊긴다() {
        wiki.hangStream = true;
        List<PageContent> seen = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> client.streamPages(9L, seen::add))
                        .isInstanceOf(ServiceUnavailableException.class)
                        .hasMessage("페이지 백필 스트림이 중단됐습니다"));
        assertThat(seen).hasSize(1); // 끊기기 전까지 받은 것은 소비됐다
    }

    @Test
    void 오류_문구에_사용자_id나_gRPC_상태가_없다() {
        wiki.failure = Status.INTERNAL;
        assertThatThrownBy(() -> client.filterVisiblePages(42L, List.of(1L)))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("42").doesNotContain("INTERNAL"));
        assertThatThrownBy(() -> client.getPage(42L))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("42").doesNotContain("INTERNAL"));
    }

    @Test
    void NOT_FOUND는_예외가_아니라_빈_값이다() {
        wiki.failure = Status.NOT_FOUND;
        assertThat(client.getPage(5L)).isEmpty();
    }

    @Test
    void 정상_응답은_그대로_돌려준다() {
        wiki.visible = Set.of(2L);
        assertThat(client.filterVisiblePages(1L, List.of(1L, 2L))).containsExactly(2L);
    }

    private static class StubWiki extends WikiContentServiceGrpc.WikiContentServiceImplBase {
        volatile boolean hang;
        volatile boolean hangStream;
        volatile Status failure;
        volatile Set<Long> visible = Set.of();

        @Override
        public void getPageContent(GetPageContentRequest request, StreamObserver<PageContent> out) {
            if (hang) return;
            if (failure != null) { out.onError(failure.asRuntimeException()); return; }
            out.onNext(PageContent.newBuilder().setPageId(request.getPageId()).build());
            out.onCompleted();
        }

        @Override
        public void filterVisiblePages(FilterVisiblePagesRequest request, StreamObserver<FilterVisiblePagesResponse> out) {
            if (hang) return;
            if (failure != null) { out.onError(failure.asRuntimeException()); return; }
            out.onNext(FilterVisiblePagesResponse.newBuilder().addAllVisiblePageIds(visible).build());
            out.onCompleted();
        }

        @Override
        public void listPageContents(ListPageContentsRequest request, StreamObserver<PageContent> out) {
            out.onNext(PageContent.newBuilder().setPageId(1L).build());
            if (hangStream) return; // 첫 건 뒤 완료하지 않는다
            out.onCompleted();
        }
    }
}
