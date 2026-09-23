package com.platform.orgservice.profile;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberEventType;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자가 남의 아바타를 올리고 지운다(스펙 §1.3 후속).
 *
 * <p>이 경로가 없으면 AGENT 멤버는 영영 얼굴이 없다 — 에이전트는 브라우저로 로그인하지 않아
 * {@code /api/org/me/avatar}를 스스로 부를 수 없다. 검증(매직 바이트·2MB)·저장·이전 오브젝트
 * 정리는 본인 경로와 같은 코드를 타고, 달라지는 것은 인가(전역 관리자)와 이력(actor≠target)뿐이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AvatarAdminApiTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};

    static final long ADMIN_ID = 300L;
    static final long TARGET_ID = 301L;
    static final long AGENT_ID = 9002L;
    static final long MISSING_ID = 9999L;

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberProfileRepository profiles;
    @Autowired MemberEventRepository memberEvents;
    @Autowired GrantEntryRepository grants;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired AvatarStorage storage;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        profiles.deleteAll();
        memberEvents.deleteAll();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        active(members, TARGET_ID, "Bob");
    }

    private MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", PNG);
    }

    /** 올린 얼굴은 멤버 목록에 바로 붙고, 지우면 바이트까지 사라진다 */
    @Test
    void 관리자가_올리면_목록에_붙고_지우면_404다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/members/{id}/avatar", TARGET_ID)
                        .file(png("bob.png")).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(TARGET_ID))
                .andExpect(jsonPath("$.avatarUrl")
                        .value(startsWith("/api/org/members/" + TARGET_ID + "/avatar?v=")))
                .andExpect(jsonPath("$.updatedAt").exists());

        String key = profiles.findById(TARGET_ID).orElseThrow().getAvatarKey();
        assertThat(key).startsWith("avatars/" + TARGET_ID + "/");

        // 본인 경로로 올린 것과 구별되지 않는다 — 목록도 바이트 조회도 같은 계약이다
        mvc.perform(get("/api/org/members/{id}/avatar", TARGET_ID).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/api/org/members").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + TARGET_ID + ")].avatarUrl")
                        .value(org.hamcrest.Matchers.hasItem(
                                startsWith("/api/org/members/" + TARGET_ID + "/avatar?v="))));

        mvc.perform(delete("/api/org/members/{id}/avatar", TARGET_ID).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/org/members/{id}/avatar", TARGET_ID).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("아바타가 없습니다"));
        assertThat(profiles.findById(TARGET_ID).orElseThrow().getAvatarKey()).isNull();
        assertThat(storedObjectExists(key)).as("삭제 후 아바타 오브젝트").isFalse();
    }

    /** 남의 얼굴을 바꾼 사람은 이력에 남는다 — 사진이 왜 바뀌었는지 물을 곳이 있어야 한다 */
    @Test
    void 남의_아바타를_바꾸면_이력에_actor가_남는다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/members/{id}/avatar", TARGET_ID)
                        .file(png("bob.png")).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/org/members/{id}/avatar", TARGET_ID).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNoContent());

        List<MemberEventType> types = memberEvents.findByMember(TARGET_ID, Limit.of(10)).stream()
                .map(e -> e.getType())
                .filter(t -> t == MemberEventType.AVATAR_CHANGED || t == MemberEventType.AVATAR_REMOVED)
                .toList();
        assertThat(types).containsExactlyInAnyOrder(
                MemberEventType.AVATAR_CHANGED, MemberEventType.AVATAR_REMOVED);
        assertThat(memberEvents.findByMember(TARGET_ID, Limit.of(10)).stream()
                .filter(e -> e.getType() == MemberEventType.AVATAR_CHANGED)
                .map(e -> e.getActorId()))
                .containsExactly(ADMIN_ID);
    }

    /** 자기 얼굴을 관리자 경로로 바꾼 것은 본인 경로와 다르지 않다 — 이력을 남기지 않는다 */
    @Test
    void 자기_아바타를_관리자_경로로_바꾸면_이력은_남지_않는다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/members/{id}/avatar", ADMIN_ID)
                        .file(png("me.png")).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk());

        assertThat(memberEvents.findByMember(ADMIN_ID, Limit.of(10)).stream()
                .map(e -> e.getType())
                .filter(t -> t == MemberEventType.AVATAR_CHANGED))
                .isEmpty();
    }

    /** 브라우저로 로그인하지 않는 AGENT 멤버의 얼굴은 이 경로로만 들어온다 */
    @Test
    void AGENT_멤버의_아바타도_올릴_수_있다() throws Exception {
        members.save(Member.agentOf(AGENT_ID, "에이전트", "agent@test.com"));

        mvc.perform(multipart(HttpMethod.PUT, "/api/org/members/{id}/avatar", AGENT_ID)
                        .file(png("agent.png")).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(AGENT_ID));

        mvc.perform(get("/api/org/members/{id}/avatar", AGENT_ID).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
    }

    /** 전역 관리자가 아니면 남의 아바타는 읽을 수만 있다 */
    @Test
    void 비관리자는_403이다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/members/{id}/avatar", ADMIN_ID)
                        .file(png("hack.png")).with(asUser(TARGET_ID, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("GLOBAL ADMIN 권한이 필요합니다"));

        mvc.perform(delete("/api/org/members/{id}/avatar", ADMIN_ID).with(asUser(TARGET_ID, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("GLOBAL ADMIN 권한이 필요합니다"));
    }

    /** 없는 멤버에게는 프로필 행을 만들지 않는다 — 올리기도 지우기도 404다 */
    @Test
    void 없는_멤버는_404다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/members/{id}/avatar", MISSING_ID)
                        .file(png("ghost.png")).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("멤버를 찾을 수 없습니다"));

        mvc.perform(delete("/api/org/members/{id}/avatar", MISSING_ID).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("멤버를 찾을 수 없습니다"));

        assertThat(profiles.findById(MISSING_ID)).isEmpty();
    }

    /**
     * 아바타가 없는 멤버를 지우는 것은 성공이다 — 지워진 상태를 만들라는 요청이 이미 충족돼 있다.
     * 다만 <b>일어나지 않은 일은 이력에 남기지 않는다</b> — 없는 사진을 지운 기록이 쌓이면 이력이 거짓말을 한다.
     */
    @Test
    void 아바타가_없어도_삭제는_204이고_이력은_남지_않는다() throws Exception {
        mvc.perform(delete("/api/org/members/{id}/avatar", TARGET_ID).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNoContent());

        assertThat(memberEvents.findByMember(TARGET_ID, Limit.of(10))).isEmpty();
    }

    /** 검증은 본인 경로와 같은 코드다 — SVG는 이름을 바꿔도 들어오지 못한다 */
    @Test
    void 이미지가_아니면_관리자_경로에서도_400이다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/members/{id}/avatar", TARGET_ID)
                        .file(new MockMultipartFile("file", "x.png", "image/png",
                                "<svg onload=alert(1)/>".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("아바타는 PNG·JPG·WebP 이미지만 올릴 수 있습니다"));

        assertThat(profiles.findById(TARGET_ID)).isEmpty();
    }

    private boolean storedObjectExists(String key) {
        try {
            return storage.open(storage.defaultBucket(), key).contentLength() >= 0;
        } catch (RuntimeException | java.io.IOException e) {
            return false;
        }
    }
}
