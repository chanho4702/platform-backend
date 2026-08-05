package com.platform.searchservice.permission;

import com.platform.proto.org.v1.Grant;
import com.platform.proto.org.v1.ListUserGrantsRequest;
import com.platform.proto.org.v1.ListUserGrantsResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import com.platform.proto.org.v1.Role;
import com.platform.searchservice.common.ServiceUnavailableException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 직렬화와 실제 blocking stub 왕복까지 포함하는 권한 클라이언트 테스트. */
class GrpcPermissionClientTest {

    private final StubOrg org = new StubOrg();
    private Server server;
    private ManagedChannel channel;
    private GrpcPermissionClient client;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName).directExecutor().addService(org).build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        client = new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow();
        server.shutdownNow();
        channel.awaitTermination(3, TimeUnit.SECONDS);
        server.awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    void GLOBAL_grant가_있으면_전체_스페이스_범위다() {
        org.grants.add(grant(ResourceType.SPACE, "10"));
        org.grants.add(grant(ResourceType.GLOBAL, ""));

        AccessScope scope = client.accessibleSpaces(7L);

        assertThat(scope.all()).isTrue();
        assertThat(scope.isEmpty()).isFalse();
        assertThat(scope.resolveFilter(java.util.Set.of())).isEmpty();
        assertThat(org.lastUserId).isEqualTo(7L);
    }

    @Test
    void SPACE_grant만_있으면_그_스페이스_집합으로_좁혀진다() {
        org.grants.add(grant(ResourceType.SPACE, "10"));
        org.grants.add(grant(ResourceType.SPACE, "20"));
        org.grants.add(grant(ResourceType.PROJECT, "99"));

        AccessScope scope = client.accessibleSpaces(8L);

        assertThat(scope.all()).isFalse();
        assertThat(scope.spaceIds()).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void 숫자가_아닌_SPACE_resourceId는_건너뛰고_나머지_grant를_쓴다() {
        org.grants.add(grant(ResourceType.SPACE, "not-a-number"));
        org.grants.add(grant(ResourceType.SPACE, "30"));

        assertThat(client.accessibleSpaces(9L).spaceIds()).containsExactly(30L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
    void org_service_가용성_장애는_빈_범위가_아니라_예외다(String code) {
        org.failure = Status.fromCode(Status.Code.valueOf(code));

        assertThatThrownBy(() -> client.accessibleSpaces(10L))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("권한 서비스에 연결할 수 없습니다");
    }

    private static Grant grant(ResourceType type, String resourceId) {
        return Grant.newBuilder()
                .setResourceType(type)
                .setResourceId(resourceId)
                .setRole(Role.VIEWER)
                .build();
    }

    private static class StubOrg extends PermissionServiceGrpc.PermissionServiceImplBase {
        final List<Grant> grants = new ArrayList<>();
        volatile Status failure;
        volatile long lastUserId;

        @Override
        public void listUserGrants(
                ListUserGrantsRequest request,
                StreamObserver<ListUserGrantsResponse> responseObserver) {
            lastUserId = request.getUserId();
            if (failure != null) {
                responseObserver.onError(failure.asRuntimeException());
                return;
            }
            responseObserver.onNext(ListUserGrantsResponse.newBuilder().addAllGrants(grants).build());
            responseObserver.onCompleted();
        }
    }
}
