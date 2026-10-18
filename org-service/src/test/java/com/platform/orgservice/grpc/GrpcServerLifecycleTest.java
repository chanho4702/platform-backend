package com.platform.orgservice.grpc;

import com.platform.common.grpc.GrpcAuthMode;
import com.platform.common.grpc.InternalTokenClientInterceptor;
import com.platform.common.grpc.InternalTokenServerInterceptor;
import com.platform.proto.org.v1.ListUserTeamsRequest;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-01 단계 1 — org gRPC 서버 구성: 권한 서비스는 토큰 인터셉터를 지나고, 리플렉션은 기본으로 빠진다.
 * 실제 포트 대신 같은 서비스 정의를 in-process 서버에 올려 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class GrpcServerLifecycleTest {

    @Autowired PermissionGrpcService permissionGrpcService;
    @Autowired InternalTokenServerInterceptor platformGrpcServerInterceptor;

    Server server;
    ManagedChannel channel;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
    }

    private PermissionServiceGrpc.PermissionServiceBlockingStub serve(List<ServerServiceDefinition> services,
                                                                        String clientToken) throws Exception {
        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder builder = InProcessServerBuilder.forName(name).directExecutor();
        services.forEach(builder::addService);
        server = builder.build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor()
                .intercept(new InternalTokenClientInterceptor(clientToken)).build();
        return PermissionServiceGrpc.newBlockingStub(channel);
    }

    private static List<String> names(List<ServerServiceDefinition> services) {
        return services.stream().map(s -> s.getServiceDescriptor().getName()).toList();
    }

    @Test
    void 기본_모드는_monitor라_토큰이_없어도_통과한다() throws Exception {
        assertThat(platformGrpcServerInterceptor.mode()).isEqualTo(GrpcAuthMode.MONITOR);
        var lifecycle = new GrpcServerLifecycle(permissionGrpcService, platformGrpcServerInterceptor, 0, false);

        var stub = serve(lifecycle.services(), "");

        assertThat(stub.listUserTeams(ListUserTeamsRequest.newBuilder().setUserId(1L).build()).getTeamIdsList()).isEmpty();
    }

    @Test
    void enforce면_토큰_없는_호출은_UNAUTHENTICATED이고_맞는_토큰은_통과한다() throws Exception {
        var enforce = new InternalTokenServerInterceptor(GrpcAuthMode.ENFORCE, "grpc-secret");
        var lifecycle = new GrpcServerLifecycle(permissionGrpcService, enforce, 0, false);

        var anonymous = serve(lifecycle.services(), "");
        assertThatThrownBy(() -> anonymous.listUserTeams(ListUserTeamsRequest.newBuilder().setUserId(1L).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
        tearDown();

        var authorized = serve(lifecycle.services(), "grpc-secret");
        assertThat(authorized.listUserTeams(ListUserTeamsRequest.newBuilder().setUserId(1L).build())).isNotNull();
    }

    @Test
    void 리플렉션은_기본으로_빠지고_명시해야_올라간다() {
        var off = new GrpcServerLifecycle(permissionGrpcService, platformGrpcServerInterceptor, 0, false);
        var on = new GrpcServerLifecycle(permissionGrpcService, platformGrpcServerInterceptor, 0, true);

        assertThat(names(off.services())).containsExactly("platform.org.v1.PermissionService");
        assertThat(names(on.services())).hasSize(2).anyMatch(n -> n.contains("reflection"));
    }
}
