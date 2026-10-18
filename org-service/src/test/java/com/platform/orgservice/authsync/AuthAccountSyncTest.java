package com.platform.orgservice.authsync;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.team.EveryoneTeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-02 — 구성원이 정지·퇴사되면 auth-server 계정을 닫고(RT 폐기·PAT 교환 거부), 해제되면 다시 연다.
 * 관리 화면 PATCH부터 동기화 주기까지 실제 경로로 검증한다. auth-server 호출만 기록용 페이크다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthAccountSyncTest {

    static final long ADMIN_ID = 100L;
    static final long USER_ID = 200L;

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired GrantEntryRepository grants;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired EveryoneTeamService everyone;
    @Autowired AuthAccountSyncService sync;
    @Autowired RecordingAuthClient auth;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberEvents.deleteAll();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        active(members, USER_ID, "Bob");
        everyone.ensure();
        everyone.add(USER_ID);
        auth.reset();
    }

    private List<Member> pending() {
        return members.findAuthSyncPending(100);
    }

    private void patchStatus(String status) throws Exception {
        mvc.perform(patch("/api/org/members/" + USER_ID).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 질의의_상태_목록은_MemberStatus와_같다() {
        // 네이티브 질의(부분 인덱스와 같은 술어)에 상태를 글자로 박았다 — enum과 어긋나면 여기서 잡는다
        assertThat(com.platform.orgservice.domain.MemberStatus.LOGIN_BLOCKED).extracting(Enum::name)
                .containsExactlyInAnyOrder("SUSPENDED", "DEACTIVATED");
    }

    @Test
    void 활성_구성원만_있으면_보낼_것이_없다() {
        assertThat(pending()).isEmpty();
        assertThat(sync.syncOnce()).isZero();
        assertThat(auth.calls).isEmpty();
    }

    @Test
    void 퇴사_처리하면_auth_server_계정을_닫고_반영을_기록한다() throws Exception {
        patchStatus("DEACTIVATED");
        assertThat(pending()).extracting(Member::getId).containsExactly(USER_ID);

        assertThat(sync.syncOnce()).isEqualTo(1);

        assertThat(auth.calls).containsExactly(new Call(USER_ID, false));
        assertThat(pending()).isEmpty();
        assertThat(sync.syncOnce()).isZero(); // 멱등 — 다시 보내지 않는다
        assertThat(auth.calls).hasSize(1);
    }

    @Test
    void 정지했다_해제하면_닫았다가_다시_연다() throws Exception {
        patchStatus("SUSPENDED");
        sync.syncOnce();
        patchStatus("ACTIVE");
        sync.syncOnce();

        assertThat(auth.calls).containsExactly(new Call(USER_ID, false), new Call(USER_ID, true));
        assertThat(pending()).isEmpty();
    }

    @Test
    void 재초대로_승인_대기가_된_퇴사자는_다시_로그인할_수_있게_연다() {
        // 재초대된 퇴사자는 로그인해서 초대를 소진해야 한다 — 닫혀 있으면 토큰을 못 받아 영영 못 들어온다
        Member m = members.findById(USER_ID).orElseThrow();
        m.deactivate(java.time.Instant.now());
        members.save(m);
        sync.syncOnce();
        m = members.findById(USER_ID).orElseThrow();
        m.reopenForInvitation();
        members.save(m);

        sync.syncOnce();

        assertThat(auth.calls).containsExactly(new Call(USER_ID, false), new Call(USER_ID, true));
    }

    @Test
    void auth_server가_실패하면_기록하지_않고_다음_주기에_다시_보낸다() throws Exception {
        patchStatus("DEACTIVATED");
        auth.next = AuthAccountClient.Result.FAILED;

        assertThat(sync.syncOnce()).isZero();
        assertThat(pending()).hasSize(1);

        auth.next = AuthAccountClient.Result.APPLIED;
        assertThat(sync.syncOnce()).isEqualTo(1);
        assertThat(auth.calls).containsExactly(new Call(USER_ID, false), new Call(USER_ID, false));
    }

    @Test
    void auth_server에_없는_사용자는_반복_시도하지_않는다() throws Exception {
        patchStatus("DEACTIVATED");
        auth.next = AuthAccountClient.Result.USER_NOT_FOUND;

        sync.syncOnce();

        assertThat(pending()).isEmpty();
    }

    @Test
    void 엔티티_저장은_반영_기록을_덮어쓰지_않는다() throws Exception {
        // 다른 트랜잭션이 들고 있던 옛 값이 더티 체킹으로 "반영됨"을 되돌리면 안 된다(insertable·updatable=false)
        patchStatus("SUSPENDED");
        Member stale = members.findById(USER_ID).orElseThrow(); // auth_synced_enabled=true를 들고 있다
        sync.syncOnce();                                         // DB는 false로 기록
        stale.reactivate();
        members.save(stale);                                     // 옛 엔티티로 ACTIVE 저장

        assertThat(pending()).extracting(Member::getId).containsExactly(USER_ID); // 원하는 true ≠ 반영된 false
        sync.syncOnce();
        assertThat(auth.calls).containsExactly(new Call(USER_ID, false), new Call(USER_ID, true));
    }

    record Call(long userId, boolean active) {}

    static class RecordingAuthClient extends AuthAccountClient {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile Result next = Result.APPLIED;

        RecordingAuthClient() {
            super("http://auth.test", "", "test-internal-token");
        }

        void reset() {
            calls.clear();
            next = Result.APPLIED;
        }

        @Override
        public Result setActive(long userId, boolean active) {
            calls.add(new Call(userId, active));
            return next;
        }
    }

    @TestConfiguration
    static class FakeAuthConfig {
        @Bean
        @Primary
        RecordingAuthClient recordingAuthClient() {
            return new RecordingAuthClient();
        }
    }
}
