package com.platform.common.grpc;

import com.platform.proto.org.v1.CheckPermissionRequest;
import com.platform.proto.org.v1.CheckPermissionResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P-01 단계 1 — 클라이언트가 토큰을 싣고, 서버는 모드대로 통과·경고·거부한다. */
class InternalTokenInterceptorTest {

    private Server server;
    private ManagedChannel channel;
    private final AtomicReference<String> seenToken = new AtomicReference<>();

    private PermissionServiceGrpc.PermissionServiceBlockingStub start(InternalTokenServerInterceptor serverInterceptor,
                                                                        InternalTokenClientInterceptor clientInterceptor) throws Exception {
        String name = InProcessServerBuilder.generateName();
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                seenToken.set(headers.get(InternalTokenServerInterceptor.HEADER));
                return next.startCall(call, headers);
            }
        };
        // 마지막에 넣은 인터셉터가 먼저 돈다 — capture가 토큰 검사보다 먼저 헤더를 본다
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(new AllowAll(), serverInterceptor, capture))
                .build().start();
        InProcessChannelBuilder builder = InProcessChannelBuilder.forName(name).directExecutor();
        if (clientInterceptor != null) builder.intercept(clientInterceptor);
        channel = builder.build();
        return PermissionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
    }

    private static CheckPermissionRequest req() {
        return CheckPermissionRequest.newBuilder().setUserId(1L).build();
    }

    @Test
    void 클라이언트_인터셉터는_메타데이터에_토큰을_싣는다() throws Exception {
        var stub = start(new InternalTokenServerInterceptor(GrpcAuthMode.ENFORCE, "t0k"),
                new InternalTokenClientInterceptor(" t0k "));
        assertThat(stub.checkPermission(req()).getAllowed()).isTrue();
        assertThat(seenToken.get()).isEqualTo("t0k");
    }

    @Test
    void monitor는_토큰이_없어도_통과시킨다() throws Exception {
        var stub = start(new InternalTokenServerInterceptor(GrpcAuthMode.MONITOR, "t0k"), null);
        assertThat(stub.checkPermission(req()).getAllowed()).isTrue();
        assertThat(seenToken.get()).isNull();
    }

    @Test
    void enforce는_토큰이_없거나_틀리면_UNAUTHENTICATED다() throws Exception {
        var stub = start(new InternalTokenServerInterceptor(GrpcAuthMode.ENFORCE, "t0k"), null);
        assertThatThrownBy(() -> stub.checkPermission(req()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
        tearDown();

        var wrong = start(new InternalTokenServerInterceptor(GrpcAuthMode.ENFORCE, "t0k"),
                new InternalTokenClientInterceptor("other"));
        assertThatThrownBy(() -> wrong.checkPermission(req()))
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void enforce인데_서버_토큰이_비면_전부_거부한다() throws Exception {
        // 토큰 env를 빠뜨린 채 enforce로 올리면 열리는 게 아니라 닫힌다(fail-closed)
        var stub = start(new InternalTokenServerInterceptor(GrpcAuthMode.ENFORCE, ""),
                new InternalTokenClientInterceptor(""));
        assertThatThrownBy(() -> stub.checkPermission(req()))
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void off는_검사하지_않는다() throws Exception {
        var stub = start(new InternalTokenServerInterceptor(GrpcAuthMode.OFF, "t0k"),
                new InternalTokenClientInterceptor("other"));
        assertThat(stub.checkPermission(req()).getAllowed()).isTrue();
    }

    @Test
    void 모드_문자열은_대소문자를_가리지_않고_모르는_값은_monitor다() {
        assertThat(GrpcAuthMode.parse("Enforce")).isEqualTo(GrpcAuthMode.ENFORCE);
        assertThat(GrpcAuthMode.parse(" off ")).isEqualTo(GrpcAuthMode.OFF);
        assertThat(GrpcAuthMode.parse("enforced")).isEqualTo(GrpcAuthMode.MONITOR);
        assertThat(GrpcAuthMode.parse(null)).isEqualTo(GrpcAuthMode.MONITOR);
    }

    private static class AllowAll extends PermissionServiceGrpc.PermissionServiceImplBase {
        @Override
        public void checkPermission(CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> out) {
            out.onNext(CheckPermissionResponse.newBuilder().setAllowed(true).build());
            out.onCompleted();
        }
    }
}
