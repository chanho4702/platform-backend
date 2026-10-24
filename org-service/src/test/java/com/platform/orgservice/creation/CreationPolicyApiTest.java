package com.platform.orgservice.creation;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.Team;
import com.platform.orgservice.domain.TeamMember;
import com.platform.orgservice.domain.TeamRole;
import com.platform.orgservice.grpc.PermissionGrpcService;
import com.platform.orgservice.repository.CreationPolicyRepository;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.proto.org.v1.Action;
import com.platform.proto.org.v1.CheckPermissionRequest;
import com.platform.proto.org.v1.CheckPermissionResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 권한·계정 점검 Top 5-③ — 스페이스·프로젝트 "생성 가능 대상" 설정과 판정.
 *
 * <p>wiki·alm이 쓰는 계약은 gRPC {@code CheckPermission(GLOBAL, "create:SPACE"|"create:PROJECT", EDIT)}이고,
 * 화면은 {@code GET /api/org/me/capabilities}를 본다. 둘이 같은 판정을 내는지 여기서 함께 건다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CreationPolicyApiTest {

    static final long ADMIN_ID = 300L;
    static final long USER_ID = 301L;
    static final long PENDING_ID = 302L;
    static final long STRANGER_ID = 303L; // member 행 없음

    @Autowired WebApplicationContext context;
    @Autowired PermissionGrpcService grpcService;
    @Autowired CreationPolicyRepository policies;
    @Autowired GrantEntryRepository grants;
    @Autowired MemberRepository members;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;

    MockMvc mvc;
    Server server;
    ManagedChannel channel;
    PermissionServiceGrpc.PermissionServiceBlockingStub stub;

    @BeforeEach
    void setup() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        policies.deleteAll();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        active(members, USER_ID, "User");
        members.save(Member.joining(PENDING_ID, "대기", "pending@test.com"));

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

    private CheckPermissionResponse canCreate(long userId, String target) {
        return stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(userId).setResourceType(ResourceType.GLOBAL)
                .setResourceId("create:" + target).setAction(Action.EDIT).build());
    }

    /** 설정이 없으면 ALL_ACTIVE(컨플루언스 기본) — 기존 동작(활성 구성원 누구나)이 그대로다. */
    @Test
    void 기본은_활성_구성원_전체이고_승인대기와_행없음은_거부한다() throws Exception {
        assertThat(canCreate(USER_ID, "SPACE").getAllowed()).isTrue();
        assertThat(canCreate(USER_ID, "PROJECT").getAllowed()).isTrue();

        CheckPermissionResponse pending = canCreate(PENDING_ID, "SPACE");
        assertThat(pending.getAllowed()).isFalse();
        assertThat(pending.getDeniedReason()).isEqualTo("PENDING");

        CheckPermissionResponse stranger = canCreate(STRANGER_ID, "SPACE");
        assertThat(stranger.getAllowed()).isFalse();
        assertThat(stranger.getDeniedReason()).isEqualTo("PENDING");

        mvc.perform(get("/api/org/settings/creation").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.space.mode").value("ALL_ACTIVE"))
                .andExpect(jsonPath("$.space.teamIds").isEmpty())
                .andExpect(jsonPath("$.project.mode").value("ALL_ACTIVE"));
    }

    @Test
    void 전역_관리자만으로_좁히면_일반_구성원은_CREATE_RESTRICTED() throws Exception {
        mvc.perform(put("/api/org/settings/creation").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"space\":{\"mode\":\"GLOBAL_ADMIN\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.space.mode").value("GLOBAL_ADMIN"))
                .andExpect(jsonPath("$.space.updatedBy").value(ADMIN_ID))
                .andExpect(jsonPath("$.project.mode").value("ALL_ACTIVE")); // 생략한 쪽은 그대로

        CheckPermissionResponse user = canCreate(USER_ID, "SPACE");
        assertThat(user.getAllowed()).isFalse();
        assertThat(user.getDeniedReason()).isEqualTo("CREATE_RESTRICTED");
        assertThat(canCreate(ADMIN_ID, "SPACE").getAllowed()).isTrue();
        assertThat(canCreate(USER_ID, "PROJECT").getAllowed()).isTrue();

        mvc.perform(get("/api/org/me/capabilities").with(asUser(USER_ID, "User")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCreateSpace").value(false))
                .andExpect(jsonPath("$.canCreateProject").value(true));
    }

    @Test
    void 특정_팀으로_좁히면_그_팀_구성원만_만든다() throws Exception {
        Team team = teams.save(Team.of("기획팀", null));
        long other = 304L;
        active(members, other, "Other");
        teamMembers.save(TeamMember.of(team.getId(), USER_ID, TeamRole.MEMBER));

        mvc.perform(put("/api/org/settings/creation").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project\":{\"mode\":\"TEAMS\",\"teamIds\":[" + team.getId() + "]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.mode").value("TEAMS"))
                .andExpect(jsonPath("$.project.teamIds[0]").value(team.getId()));

        assertThat(canCreate(USER_ID, "PROJECT").getAllowed()).isTrue();
        assertThat(canCreate(other, "PROJECT").getDeniedReason()).isEqualTo("CREATE_RESTRICTED");
        assertThat(canCreate(ADMIN_ID, "PROJECT").getAllowed()).isTrue(); // 전역 관리자는 팀과 무관
    }

    @Test
    void TEAMS는_팀이_필요하고_없는_팀은_404다() throws Exception {
        mvc.perform(put("/api/org/settings/creation").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"space\":{\"mode\":\"TEAMS\",\"teamIds\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("팀을 하나 이상 지정하세요"));
        mvc.perform(put("/api/org/settings/creation").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"space\":{\"mode\":\"TEAMS\",\"teamIds\":[999999]}}"))
                .andExpect(status().isNotFound());
        assertThat(policies.findAll()).isEmpty();
    }

    @Test
    void 설정은_전역_관리자만_보고_바꾼다() throws Exception {
        mvc.perform(get("/api/org/settings/creation").with(asUser(USER_ID, "User")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/org/settings/creation").with(asUser(USER_ID, "User"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"space\":{\"mode\":\"ALL_ACTIVE\"}}"))
                .andExpect(status().isForbidden());
        assertThat(policies.findAll()).isEmpty();
    }

    /** 승인 대기도 /api/org/me/** 는 읽는다 — 버튼을 숨길 수 있게 둘 다 false로 준다. */
    @Test
    void 승인대기의_capabilities는_둘_다_false() throws Exception {
        mvc.perform(get("/api/org/me/capabilities").with(asUser(PENDING_ID, "대기")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCreateSpace").value(false))
                .andExpect(jsonPath("$.canCreateProject").value(false));
    }

    /** 대상이 틀리면 호출측 버그다 — 정상 거부와 구분되게 INVALID_ARGUMENT(소비자는 fail-closed). */
    @Test
    void 알_수_없는_생성_대상은_INVALID_ARGUMENT() {
        assertThatThrownBy(() -> canCreate(USER_ID, "PAGE")).hasMessageContaining("INVALID_ARGUMENT");
    }

    /** 평소의 GLOBAL 판정(resource_id 빈 값)은 그대로 grant를 본다 — 생성 판정과 섞이지 않는다. */
    @Test
    void 빈_resource_id의_GLOBAL_판정은_예전과_같다() {
        CheckPermissionResponse user = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(USER_ID).setResourceType(ResourceType.GLOBAL).setAction(Action.ADMIN).build());
        CheckPermissionResponse admin = stub.checkPermission(CheckPermissionRequest.newBuilder()
                .setUserId(ADMIN_ID).setResourceType(ResourceType.GLOBAL).setAction(Action.ADMIN).build());
        assertThat(user.getAllowed()).isFalse();
        assertThat(user.getDeniedReason()).isEqualTo("NO_GRANT");
        assertThat(admin.getAllowed()).isTrue();
    }
}
