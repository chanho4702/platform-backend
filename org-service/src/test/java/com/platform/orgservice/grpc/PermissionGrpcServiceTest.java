package com.platform.orgservice.grpc;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.proto.org.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PermissionGrpcServiceTest {

    @Autowired PermissionGrpcService grpcService;
    @Autowired GrantEntryRepository grants;

    Server server;
    ManagedChannel channel;
    PermissionServiceGrpc.PermissionServiceBlockingStub stub;

    @BeforeEach
    void setup() throws IOException {
        grants.deleteAll();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(grpcService).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = PermissionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void teardown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void CheckPermission_grant보유자는_allowed_true() {
        grants.save(GrantEntry.of(SubjectType.USER, 1L, ResourceKind.SPACE, "sp-1", GrantRole.EDITOR));

        CheckPermissionResponse res = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(1L).setResourceType(ResourceType.SPACE).setResourceId("sp-1")
                .setAction(Action.EDIT).build());

        assertThat(res.getAllowed()).isTrue();
        assertThat(res.getEffectiveRole()).isEqualTo(Role.EDITOR);
    }

    @Test
    void CheckPermission_무grant는_allowed_false_role_UNSPECIFIED() {
        CheckPermissionResponse res = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(9L).setResourceType(ResourceType.SPACE).setResourceId("sp-1")
                .setAction(Action.VIEW).build());

        assertThat(res.getAllowed()).isFalse();
        assertThat(res.getEffectiveRole()).isEqualTo(Role.ROLE_UNSPECIFIED);
    }

    @Test
    void ListUserGrants_리소스타입_필터가_동작한다() {
        grants.save(GrantEntry.of(SubjectType.USER, 2L, ResourceKind.SPACE, "sp-1", GrantRole.VIEWER));
        grants.save(GrantEntry.of(SubjectType.USER, 2L, ResourceKind.PROJECT, "pj-1", GrantRole.ADMIN));

        ListUserGrantsResponse all = stub.listUserGrants(
                ListUserGrantsRequest.newBuilder().setUserId(2L).build());
        ListUserGrantsResponse spaceOnly = stub.listUserGrants(
                ListUserGrantsRequest.newBuilder().setUserId(2L).setResourceType(ResourceType.SPACE).build());

        assertThat(all.getGrantsList()).hasSize(2);
        assertThat(spaceOnly.getGrantsList()).hasSize(1);
        assertThat(spaceOnly.getGrants(0).getRole()).isEqualTo(Role.VIEWER);
    }

    @Test
    void CreateGrant_신규는_created_true_중복은_false_멱등() {
        CreateGrantRequest req = CreateGrantRequest.newBuilder()
                .setUserId(5L).setResourceType(ResourceType.SPACE).setResourceId("7")
                .setRole(Role.ROLE_ADMIN).build();

        assertThat(stub.createGrant(req).getCreated()).isTrue();
        assertThat(stub.createGrant(req).getCreated()).isFalse(); // 멱등
        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, 5L, ResourceKind.SPACE, "7")).isPresent();
    }

    @Test
    void CreateGrant_GLOBAL은_resourceId가_빈값으로_정규화된다() {
        stub.createGrant(CreateGrantRequest.newBuilder()
                .setUserId(6L).setResourceType(ResourceType.GLOBAL).setResourceId("junk")
                .setRole(Role.ROLE_ADMIN).build());

        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, 6L, ResourceKind.GLOBAL, "")).isPresent();
    }

    @Test
    void CreateGrant_UNSPECIFIED_인자는_INVALID_ARGUMENT() {
        assertThatThrownBy(() -> stub.createGrant(CreateGrantRequest.newBuilder()
                .setUserId(5L).setResourceType(ResourceType.RESOURCE_TYPE_UNSPECIFIED)
                .setRole(Role.ROLE_ADMIN).build()))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }
}
