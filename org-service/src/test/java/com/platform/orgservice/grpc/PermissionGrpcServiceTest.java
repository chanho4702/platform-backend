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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    /**
     * 상태가 ACTIVE가 아니면 grant를 보기 전에 거부한다(U1 fail-closed).
     *
     * 승인 대기·정지·퇴사자는 권한을 그대로 들고 있을 수 있는데, 그 권한이 살아 있으면 wiki·alm은
     * 아무 일도 없었던 것처럼 문서를 열어 준다. 상태 판정을 org 한 곳에 두면 소비 서비스가 각자 기억할 필요가 없다.
     */
    /**
     * 상태로 막힌 거부는 <b>정상 응답</b>이다 — 오류 상태(UNAVAILABLE 등)로 올리지 않는다.
     * 소비자가 "org가 죽었다"와 "이 사람이 막혔다"를 구분할 수 있어야 하기 때문이다.
     */
    @Test
    void CheckPermission_비활성_계정은_grant가_있어도_거부하고_사유를_준다() {
        grants.save(GrantEntry.of(SubjectType.USER, 42L, ResourceKind.SPACE, "sp-1", GrantRole.ADMIN));
        memberRepo.save(com.platform.orgservice.domain.Member.joining(42L, "승인대기", "pending@test.com"));

        CheckPermissionResponse res = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(42L).setResourceType(ResourceType.SPACE).setResourceId("sp-1")
                .setAction(Action.VIEW).build());

        assertThat(res.getAllowed()).isFalse();
        assertThat(res.getEffectiveRole()).isEqualTo(Role.ROLE_UNSPECIFIED);
        assertThat(res.getDeniedReason()).isEqualTo("PENDING");
    }

    /** grant가 아예 없는 것과 역할이 모자란 것은 화면이 사용자에게 해야 할 말이 다르다. */
    @Test
    void CheckPermission_거부_사유가_grant_없음과_역할_부족을_구분한다() {
        CheckPermissionResponse noGrant = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(51L).setResourceType(ResourceType.SPACE).setResourceId("sp-9")
                .setAction(Action.VIEW).build());
        assertThat(noGrant.getAllowed()).isFalse();
        assertThat(noGrant.getDeniedReason()).isEqualTo("NO_GRANT");

        grants.save(GrantEntry.of(SubjectType.USER, 51L, ResourceKind.SPACE, "sp-9", GrantRole.VIEWER));
        CheckPermissionResponse tooLow = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(51L).setResourceType(ResourceType.SPACE).setResourceId("sp-9")
                .setAction(Action.EDIT).build());
        assertThat(tooLow.getAllowed()).isFalse();
        assertThat(tooLow.getDeniedReason()).isEqualTo("INSUFFICIENT_ROLE");

        CheckPermissionResponse allowed = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(51L).setResourceType(ResourceType.SPACE).setResourceId("sp-9")
                .setAction(Action.VIEW).build());
        assertThat(allowed.getAllowed()).isTrue();
        assertThat(allowed.getDeniedReason()).isEmpty();
    }

    /**
     * id로 사람을 읽는다(0.16.0). LookupMembers와 방향이 반대이고, <b>활성 여부로 거르지 않는다</b> —
     * 퇴사자에게 알림을 안 보내는 것과 퇴사자 이름을 못 그리는 것은 다른 문제라 상태를 실어 준다.
     */
    @Test
    void GetMembers는_id로_이름과_이메일을_주고_상태로_거르지_않는다() {
        memberRepo.save(com.platform.orgservice.domain.Member.of(61L, "활성사람", "active@test.com"));
        var 퇴사자 = com.platform.orgservice.domain.Member.of(62L, "퇴사자", "gone@test.com");
        퇴사자.deactivate(java.time.Instant.now());
        memberRepo.save(퇴사자);

        GetMembersResponse res = stub.getMembers(GetMembersRequest.newBuilder()
                .addIds(61L).addIds(62L).addIds(9999L).build());

        assertThat(res.getMembersList()).hasSize(2); // 없는 id는 응답에서 빠진다
        assertThat(res.getMembersList()).anySatisfy(m -> {
            assertThat(m.getId()).isEqualTo(61L);
            assertThat(m.getEmail()).isEqualTo("active@test.com");
            assertThat(m.getStatus()).isEqualTo("ACTIVE");
            assertThat(m.getKind()).isEqualTo("HUMAN");
        });
        assertThat(res.getMembersList()).anySatisfy(m ->
                assertThat(m.getStatus()).isEqualTo("DEACTIVATED"));
    }

    @Test
    void GetMembers는_빈_요청에_빈_응답을_준다() {
        assertThat(stub.getMembers(GetMembersRequest.newBuilder().build()).getMembersList()).isEmpty();
    }

    @Test
    void GetMembers는_상한을_넘으면_INVALID_ARGUMENT다() {
        GetMembersRequest.Builder req = GetMembersRequest.newBuilder();
        for (long i = 0; i < 201; i++) req.addIds(i);

        assertThatThrownBy(() -> stub.getMembers(req.build()))
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    /** member 행이 아예 없으면 상태로 막지 않는다 — 미러링 순서에 따라 기존 사용자가 무작위로 차단된다. */
    @Test
    void CheckPermission_member_행이_없으면_평소대로_판정한다() {
        grants.save(GrantEntry.of(SubjectType.USER, 43L, ResourceKind.SPACE, "sp-1", GrantRole.EDITOR));

        CheckPermissionResponse res = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(43L).setResourceType(ResourceType.SPACE).setResourceId("sp-1")
                .setAction(Action.EDIT).build());

        assertThat(res.getAllowed()).isTrue();
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

    // ── LookupMembers / LookupTeams (0.15.0) — 이관이 원본 사람·그룹을 짝짓는 창구 ──

    @Test
    void LookupMembers_이메일과_username_local_part로_찾고_대소문자를_무시한다() {
        memberRepo.save(com.platform.orgservice.domain.Member.of(42L, "앨리스", "Alice@Example.com"));
        memberRepo.save(com.platform.orgservice.domain.Member.of(43L, "밥", "bob@example.com"));

        LookupMembersResponse response = stub.lookupMembers(LookupMembersRequest.newBuilder()
                .addEmails("  ALICE@example.com ")   // trim + 대소문자 무시
                .addUsernames("BOB")                 // username은 이메일 local-part로 본다
                .build());

        assertThat(response.getMatchesList())
                .extracting(MemberMatch::getQuery, MemberMatch::getMemberId, MemberMatch::getDisplayName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("  ALICE@example.com ", 42L, "앨리스"),
                        org.assertj.core.groups.Tuple.tuple("BOB", 43L, "밥"));
    }

    /** 없는 사람은 응답에서 빠진다 — 호출측이 "못 찾았다"를 fail-closed로 처리한다. */
    @Test
    void LookupMembers_못_찾은_질의는_응답에_담기지_않는다() {
        memberRepo.save(com.platform.orgservice.domain.Member.of(42L, "앨리스", "alice@example.com"));

        LookupMembersResponse response = stub.lookupMembers(LookupMembersRequest.newBuilder()
                .addEmails("alice@example.com").addEmails("ghost@example.com")
                .addUsernames("ghost")
                .build());

        assertThat(response.getMatchesList()).extracting(MemberMatch::getQuery)
                .containsExactly("alice@example.com");
    }

    /** 퇴사자에게 이관 문서를 붙이면 아무도 손댈 수 없는 문서가 된다. */
    @Test
    void LookupMembers_비활성_계정은_매칭하지_않는다() {
        memberRepo.saveAndFlush(com.platform.orgservice.domain.Member.of(44L, "퇴사자", "gone@example.com"));
        // 상태를 바꾸는 도메인 경로가 아직 없어 원장을 직접 눌러 둔다(스키마는 DEACTIVATED를 허용한다).
        jdbc.update("update member set status = 'DEACTIVATED' where id = 44");

        LookupMembersResponse response = stub.lookupMembers(LookupMembersRequest.newBuilder()
                .addEmails("gone@example.com").addUsernames("gone").build());

        assertThat(response.getMatchesList()).isEmpty();
    }

    /** 후보가 둘이면 누구인지 모른다 — 하나를 고르면 남의 이름으로 문서가 쓰인다. */
    @Test
    void LookupMembers_후보가_둘_이상인_질의는_매칭하지_않는다() {
        memberRepo.save(com.platform.orgservice.domain.Member.of(45L, "김운영", "ops@a.example.com"));
        memberRepo.save(com.platform.orgservice.domain.Member.of(46L, "이운영", "ops@b.example.com"));

        LookupMembersResponse response = stub.lookupMembers(
                LookupMembersRequest.newBuilder().addUsernames("ops").build());

        assertThat(response.getMatchesList()).isEmpty();
    }

    @Test
    void LookupTeams_팀_이름을_대소문자_무시로_찾고_없는_이름은_뺀다() {
        var team = teamRepo.save(com.platform.orgservice.domain.Team.of("플랫폼팀", null));

        LookupTeamsResponse response = stub.lookupTeams(LookupTeamsRequest.newBuilder()
                .addNames(" 플랫폼팀 ").addNames("없는팀").build());

        assertThat(response.getMatchesList())
                .extracting(TeamMatch::getQuery, TeamMatch::getTeamId, TeamMatch::getName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(" 플랫폼팀 ", team.getId(), "플랫폼팀"));
    }

    @Test
    void Lookup은_요청_상한_200을_넘으면_INVALID_ARGUMENT() {
        LookupMembersRequest.Builder members = LookupMembersRequest.newBuilder();
        for (int i = 0; i < 201; i++) members.addEmails("user" + i + "@example.com");
        assertThatThrownBy(() -> stub.lookupMembers(members.build()))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");

        LookupTeamsRequest.Builder teams = LookupTeamsRequest.newBuilder();
        for (int i = 0; i < 201; i++) teams.addNames("team" + i);
        assertThatThrownBy(() -> stub.lookupTeams(teams.build()))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }
}
