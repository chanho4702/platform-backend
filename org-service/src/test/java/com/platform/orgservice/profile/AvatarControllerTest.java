package com.platform.orgservice.profile;

import com.platform.orgservice.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멤버 아바타 계약(스펙 §1.3). 바이트는 별도 저장소(테스트는 로컬 파일)에, 키는 member_profile에 —
 * member 테이블은 건드리지 않는다. 형식은 클라이언트가 보낸 Content-Type이 아니라 매직 바이트로
 * 판별한다(SVG가 프로필 사진 이름으로 들어오면 안 된다).
 */
@SpringBootTest
@ActiveProfiles("test")
class AvatarControllerTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired MemberProfileRepository profiles;
    @Autowired AvatarStorage storage;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        profiles.deleteAll();
        members.deleteAll();
        // U1부터 처음 보는 사용자는 PENDING으로 격리된다 — 아바타 화면 테스트는 활성 사용자로 시작한다
        active(members, 7L, "Alice");
        active(members, 8L, "Bob");
    }

    private MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", PNG);
    }

    @Test
    void 올리면_조회되고_지우면_404다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/me/avatar").file(png("me.png")).with(asUser(7, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(7))
                .andExpect(jsonPath("$.avatarUrl").value(startsWith("/api/org/members/7/avatar?v=")))
                // 프론트가 캐시 키로 쓰는 값 — 숫자 타임스탬프가 아니라 ISO-8601 문자열이다
                .andExpect(jsonPath("$.updatedAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z")));

        // 보는 것은 로그인한 누구나 — 담당자 셀·코멘트에 다른 사람 얼굴이 떠야 한다
        // ?v= 캐시버스터는 서버가 읽지 않는다 — 붙어 있어도 같은 바이트가 나온다
        mvc.perform(get("/api/org/members/{id}/avatar?v=1757000000000", 7).with(asUser(8, "Bob")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));

        mvc.perform(delete("/api/org/me/avatar").with(asUser(7, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/org/members/{id}/avatar", 7).with(asUser(7, "Alice")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("아바타가 없습니다"));
    }

    @Test
    void 이미지가_아니거나_2MB를_넘으면_거부한다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/me/avatar")
                        .file(new MockMultipartFile("file", "x.svg", "image/png",
                                "<svg onload=alert(1)/>".getBytes(StandardCharsets.UTF_8)))
                        .with(asUser(7, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("아바타는 PNG·JPG·WebP 이미지만 올릴 수 있습니다"));

        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/me/avatar")
                        .file(new MockMultipartFile("file", "big.png", "image/png", big))
                        .with(asUser(7, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("아바타는 2MB 이하 이미지여야 합니다"));

        mvc.perform(multipart(HttpMethod.PUT, "/api/org/me/avatar")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .with(asUser(7, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("빈 파일은 올릴 수 없습니다"));

        mvc.perform(get("/api/org/members/{id}/avatar", 7).with(asUser(7, "Alice")))
                .andExpect(status().isNotFound());
    }

    /** 멤버 목록과 /api/org/me가 같은 URL을 싣는다 — 프론트는 목록 하나로 얼굴을 붙인다 */
    @Test
    void 멤버_목록과_내_프로필에_반영된다() throws Exception {
        mvc.perform(get("/api/org/me").with(asUser(7, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.avatarUpdatedAt").doesNotExist());

        mvc.perform(multipart(HttpMethod.PUT, "/api/org/me/avatar").file(png("me.png")).with(asUser(7, "Alice")))
                .andExpect(status().isOk());
        // 아바타가 없는 멤버도 목록에 그대로 있고 avatarUrl만 null이다
        mvc.perform(get("/api/org/me").with(asUser(8, "Bob"))).andExpect(status().isOk());

        mvc.perform(get("/api/org/me").with(asUser(7, "Alice")))
                .andExpect(jsonPath("$.avatarUrl").value(startsWith("/api/org/members/7/avatar?v=")))
                .andExpect(jsonPath("$.avatarUpdatedAt").exists());

        String body = mvc.perform(get("/api/org/members").with(asUser(7, "Alice")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> list = com.jayway.jsonpath.JsonPath.parse(body).read("$");
        java.util.Map<Long, Object> urlById = new java.util.HashMap<>();
        for (var entry : list) {
            urlById.put(((Number) entry.get("id")).longValue(), entry.get("avatarUrl"));
        }
        assertThat((String) urlById.get(7L)).startsWith("/api/org/members/7/avatar?v=");
        assertThat(urlById).containsKey(8L);
        assertThat(urlById.get(8L)).isNull();
    }

    /** 다시 올리면 새 오브젝트로 갈아타고 이전 바이트는 저장소에서 사라진다 — 아니면 고아가 쌓인다 */
    @Test
    void 다시_올리면_새_키로_갈아타고_이전_오브젝트를_지운다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/me/avatar").file(png("one.png")).with(asUser(7, "Alice")))
                .andExpect(status().isOk());
        String first = profiles.findById(7L).orElseThrow().getAvatarKey();
        assertThat(storedObjectExists(first)).isTrue();

        mvc.perform(multipart(HttpMethod.PUT, "/api/org/me/avatar").file(png("two.png")).with(asUser(7, "Alice")))
                .andExpect(status().isOk());
        String second = profiles.findById(7L).orElseThrow().getAvatarKey();

        assertThat(second).isNotEqualTo(first).startsWith("avatars/7/");
        assertThat(storedObjectExists(first)).as("이전 아바타 오브젝트").isFalse();
        assertThat(storedObjectExists(second)).as("새 아바타 오브젝트").isTrue();
        mvc.perform(get("/api/org/members/{id}/avatar", 7).with(asUser(7, "Alice")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
    }

    /** 지우면 메타뿐 아니라 바이트도 없어진다 */
    @Test
    void 지우면_저장소에서도_사라진다() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/org/me/avatar").file(png("me.png")).with(asUser(7, "Alice")))
                .andExpect(status().isOk());
        String key = profiles.findById(7L).orElseThrow().getAvatarKey();
        assertThat(storedObjectExists(key)).isTrue();

        mvc.perform(delete("/api/org/me/avatar").with(asUser(7, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(profiles.findById(7L).orElseThrow().getAvatarKey()).isNull();
        assertThat(storedObjectExists(key)).as("삭제 후 아바타 오브젝트").isFalse();
    }

    @Test
    void 무토큰은_401() throws Exception {
        mvc.perform(get("/api/org/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/org/members/{id}/avatar", 7)).andExpect(status().isUnauthorized());
    }

    /** 저장소에 바이트가 남아 있는지 — 로컬 파일 저장소는 없는 키를 열면 503을 던진다 */
    private boolean storedObjectExists(String key) {
        try {
            return storage.open(storage.defaultBucket(), key).contentLength() >= 0;
        } catch (RuntimeException | java.io.IOException e) {
            return false;
        }
    }
}
