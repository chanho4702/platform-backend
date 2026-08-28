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
    @Autowired com.platform.orgservice.repository.TeamMemberRepository teamMembers;
    @Autowired com.platform.orgservice.repository.TeamRepository teamRepo;
    @Autowired com.platform.orgservice.repository.MemberRepository memberRepo;

    Server server;
    ManagedChannel channel;
    PermissionServiceGrpc.PermissionServiceBlockingStub stub;

    @BeforeEach
    void setup() throws IOException {
        grants.deleteAll();
        teamMembers.deleteAll();
        teamRepo.deleteAll();
        memberRepo.deleteAll();
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

    // ── RevokeGrant (v0.3.0) — 리소스가 사라졌을 때 고아 grant 정리 ──

    @Test
    void RevokeGrant_user_id가_0이면_그_리소스의_grant를_전부_회수한다() {
        for (long userId : new long[] {11L, 12L}) {
            stub.createGrant(CreateGrantRequest.newBuilder()
                    .setUserId(userId).setResourceType(ResourceType.SPACE).setResourceId("77")
                    .setRole(Role.ROLE_ADMIN).build());
        }
        // 다른 리소스의 grant는 남아야 한다
        stub.createGrant(CreateGrantRequest.newBuilder()
                .setUserId(11L).setResourceType(ResourceType.SPACE).setResourceId("88")
                .setRole(Role.VIEWER).build());

        RevokeGrantResponse res = stub.revokeGrant(RevokeGrantRequest.newBuilder()
                .setResourceType(ResourceType.SPACE).setResourceId("77").build());

        assertThat(res.getRevoked()).isEqualTo(2);
        assertThat(grants.findByResourceTypeAndResourceId(ResourceKind.SPACE, "77")).isEmpty();
        assertThat(grants.findByResourceTypeAndResourceId(ResourceKind.SPACE, "88")).hasSize(1);
    }

    @Test
    void RevokeGrant_user_id를_지정하면_그_사용자_것만_회수한다() {
        for (long userId : new long[] {21L, 22L}) {
            stub.createGrant(CreateGrantRequest.newBuilder()
                    .setUserId(userId).setResourceType(ResourceType.SPACE).setResourceId("99")
                    .setRole(Role.EDITOR).build());
        }

        RevokeGrantResponse res = stub.revokeGrant(RevokeGrantRequest.newBuilder()
                .setResourceType(ResourceType.SPACE).setResourceId("99").setUserId(21L).build());

        assertThat(res.getRevoked()).isEqualTo(1);
        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, 21L, ResourceKind.SPACE, "99")).isEmpty();
        assertThat(grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, 22L, ResourceKind.SPACE, "99")).isPresent();
    }

    /** 삭제는 재시도될 수 있다 — 두 번째 호출이 에러가 되면 호출측이 실패로 오해한다. */
    @Test
    void RevokeGrant_대상이_없으면_0을_반환한다_멱등() {
        RevokeGrantResponse res = stub.revokeGrant(RevokeGrantRequest.newBuilder()
                .setResourceType(ResourceType.SPACE).setResourceId("존재하지않음").build());

        assertThat(res.getRevoked()).isZero();
    }

    @Test
    void RevokeGrant_UNSPECIFIED_리소스타입은_INVALID_ARGUMENT() {
        assertThatThrownBy(() -> stub.revokeGrant(RevokeGrantRequest.newBuilder()
                .setResourceType(ResourceType.RESOURCE_TYPE_UNSPECIFIED).setResourceId("1").build()))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    @Test
    void ListUserTeams_팀_멤버십을_돌려주고_무소속은_빈_목록() {
        var team = teamRepo.save(com.platform.orgservice.domain.Team.of("플랫폼팀", null));
        teamMembers.save(com.platform.orgservice.domain.TeamMember.of(team.getId(), 42L,
                com.platform.orgservice.domain.TeamRole.MEMBER));

        ListUserTeamsResponse mine = stub.listUserTeams(
                ListUserTeamsRequest.newBuilder().setUserId(42L).build());
        assertThat(mine.getTeamIdsList()).containsExactly(team.getId());

        ListUserTeamsResponse none = stub.listUserTeams(
                ListUserTeamsRequest.newBuilder().setUserId(99L).build());
        assertThat(none.getTeamIdsList()).isEmpty();
    }

    @Test
    void ValidatePrincipals_존재하지_않거나_잘못된_주체만_missing으로_돌려준다() {
        memberRepo.save(com.platform.orgservice.domain.Member.of(42L, "앨리스", "alice@example.com"));
        var team = teamRepo.save(com.platform.orgservice.domain.Team.of("플랫폼팀", null));

        ValidatePrincipalsResponse response = stub.validatePrincipals(
                ValidatePrincipalsRequest.newBuilder()
                        .addPrincipals(PrincipalRef.newBuilder()
                                .setKind(PrincipalKind.PRINCIPAL_USER).setId(42L))
                        .addPrincipals(PrincipalRef.newBuilder()
                                .setKind(PrincipalKind.PRINCIPAL_TEAM).setId(team.getId()))
                        .addPrincipals(PrincipalRef.newBuilder()
                                .setKind(PrincipalKind.PRINCIPAL_USER).setId(999L))
                        .addPrincipals(PrincipalRef.newBuilder()
                                .setKind(PrincipalKind.PRINCIPAL_KIND_UNSPECIFIED).setId(1L))
                        .build());

        assertThat(response.getMissingList())
                .extracting(PrincipalRef::getKind, PrincipalRef::getId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PrincipalKind.PRINCIPAL_USER, 999L),
                        org.assertj.core.groups.Tuple.tuple(PrincipalKind.PRINCIPAL_KIND_UNSPECIFIED, 1L));
    }
}
