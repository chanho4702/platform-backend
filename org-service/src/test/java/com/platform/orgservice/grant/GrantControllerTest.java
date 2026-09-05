package com.platform.orgservice.grant;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.repository.GrantEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class GrantControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired GrantEntryRepository grants;
    @Autowired com.platform.orgservice.repository.GrantAuditRepository audits;
    @Autowired com.platform.orgservice.repository.MemberRepository members;
    MockMvc mvc;

    static final long ADMIN_ID = 100L;
    static final long USER_ID = 200L;
    /** 특정 스페이스의 ADMIN — 전역 권한은 없다. */
    static final long SPACE_ADMIN_ID = 300L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        audits.deleteAll();
        grants.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        // U1부터 처음 보는 사용자는 PENDING으로 격리된다 — 권한 화면 테스트는 활성 사용자로 시작한다
        active(members, ADMIN_ID, "Admin");
        active(members, USER_ID, "Bob");
        active(members, SPACE_ADMIN_ID, "SpaceAdmin");
    }

    /**
     * 스페이스 소유자가 자기 공간 권한을 못 만지면 사람을 초대할 방법이 없다.
     * 컨플루언스도 스페이스 관리자가 그 스페이스 권한을 관리한다(2026-08-29).
     */
    @Test
    void 스페이스_ADMIN은_자기_스페이스_권한을_관리한다() throws Exception {
        grants.save(GrantEntry.of(SubjectType.USER, SPACE_ADMIN_ID, ResourceKind.SPACE, "sp-1", GrantRole.ADMIN));
        String body = "{\"subjectType\":\"USER\",\"subjectId\":200,\"resourceType\":\"SPACE\","
                + "\"resourceId\":\"sp-1\",\"role\":\"EDITOR\"}";

        String created = mvc.perform(post("/api/org/grants").with(asUser(SPACE_ADMIN_ID, "SpaceAdmin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long grantId = com.jayway.jsonpath.JsonPath.parse(created).read("$.id", Long.class);

        mvc.perform(get("/api/org/grants?resourceType=SPACE&resourceId=sp-1")
                        .with(asUser(SPACE_ADMIN_ID, "SpaceAdmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(delete("/api/org/grants/" + grantId).with(asUser(SPACE_ADMIN_ID, "SpaceAdmin")))
                .andExpect(status().isNoContent());
    }

    /** 자기 스페이스의 관리자라고 남의 스페이스나 전역 권한까지 만질 수는 없다. */
    @Test
    void 스페이스_ADMIN은_다른_리소스_권한은_만지지_못한다() throws Exception {
        grants.save(GrantEntry.of(SubjectType.USER, SPACE_ADMIN_ID, ResourceKind.SPACE, "sp-1", GrantRole.ADMIN));

        mvc.perform(post("/api/org/grants").with(asUser(SPACE_ADMIN_ID, "SpaceAdmin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"USER\",\"subjectId\":200,\"resourceType\":\"SPACE\","
                                + "\"resourceId\":\"sp-2\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/org/grants").with(asUser(SPACE_ADMIN_ID, "SpaceAdmin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"USER\",\"subjectId\":200,\"resourceType\":\"GLOBAL\","
                                + "\"resourceId\":null,\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/org/grants?resourceType=SPACE&resourceId=sp-2")
                        .with(asUser(SPACE_ADMIN_ID, "SpaceAdmin")))
                .andExpect(status().isForbidden());
    }

    /** EDITOR는 관리 권한이 아니다 — 자기가 속한 스페이스라도 권한을 못 만진다. */
    @Test
    void 스페이스_EDITOR는_권한을_관리하지_못한다() throws Exception {
        grants.save(GrantEntry.of(SubjectType.USER, USER_ID, ResourceKind.SPACE, "sp-1", GrantRole.EDITOR));

        mvc.perform(get("/api/org/grants?resourceType=SPACE&resourceId=sp-1").with(asUser(USER_ID, "Bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    void grant_부여는_ADMIN만_가능하다() throws Exception {
        String body = "{\"subjectType\":\"USER\",\"subjectId\":200,\"resourceType\":\"SPACE\",\"resourceId\":\"sp-1\",\"role\":\"EDITOR\"}";

        mvc.perform(post("/api/org/grants").with(asUser(USER_ID, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/org/grants").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EDITOR"));
    }

    @Test
    void 리소스별_grant_목록을_조회하고_회수한다() throws Exception {
        String body = "{\"subjectType\":\"USER\",\"subjectId\":200,\"resourceType\":\"SPACE\",\"resourceId\":\"sp-1\",\"role\":\"VIEWER\"}";
        String created = mvc.perform(post("/api/org/grants").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        long grantId = com.jayway.jsonpath.JsonPath.parse(created).read("$.id", Long.class);

        mvc.perform(get("/api/org/grants?resourceType=SPACE&resourceId=sp-1").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(delete("/api/org/grants/" + grantId).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 중복_grant는_400() throws Exception {
        String body = "{\"subjectType\":\"USER\",\"subjectId\":200,\"resourceType\":\"SPACE\",\"resourceId\":\"sp-1\",\"role\":\"VIEWER\"}";
        mvc.perform(post("/api/org/grants").with(asUser(ADMIN_ID, "Admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/org/grants").with(asUser(ADMIN_ID, "Admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void 멤버_목록은_인증만_있으면_조회된다() throws Exception {
        mvc.perform(get("/api/org/members").with(asUser(USER_ID, "Bob")))
                .andExpect(status().isOk());
    }

    @Test
    void GLOBAL_grant는_resourceId가_빈값으로_정규화된다() throws Exception {
        String body = "{\"subjectType\":\"USER\",\"subjectId\":300,\"resourceType\":\"GLOBAL\",\"resourceId\":\"junk\",\"role\":\"ADMIN\"}";
        mvc.perform(post("/api/org/grants").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceId").value(""));
    }

    /**
     * 권한 변경 감사(W23).
     *
     * 감사에서 가장 궁금한 것이 "누가 이 사람에게 권한을 줬나"인데, 그 조작은 여기서 일어나고
     * wiki-backend는 보지 못한다 — 그래서 이 기록이 없으면 감사 로그에 그 절반이 통째로 빈다.
     */
    @Test
    void 권한_부여와_회수가_감사에_남는다() throws Exception {
        String body = "{\"subjectType\":\"USER\",\"subjectId\":200,\"resourceType\":\"SPACE\","
                + "\"resourceId\":\"sp-9\",\"role\":\"EDITOR\"}";
        String created = mvc.perform(post("/api/org/grants").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long grantId = com.jayway.jsonpath.JsonPath.parse(created).read("$.id", Long.class);

        mvc.perform(delete("/api/org/grants/" + grantId).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNoContent());

        // 최신이 먼저 — 회수가 위, 부여가 아래
        mvc.perform(get("/api/org/grants/audit?resourceType=SPACE&resourceId=sp-9")
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$[1].action").value("GRANT_GRANTED"))
                .andExpect(jsonPath("$[1].detail").value("EDITOR"))
                .andExpect(jsonPath("$[1].actorId").value(String.valueOf(ADMIN_ID)));
    }

    /** 조회 범위는 grant 목록과 같다 — 관리자가 아니면 이력도 볼 수 없다. */
    @Test
    void 관리자가_아니면_권한_이력을_볼_수_없다() throws Exception {
        mvc.perform(get("/api/org/grants/audit?resourceType=SPACE&resourceId=sp-9")
                        .with(asUser(USER_ID, "User")))
                .andExpect(status().isForbidden());
    }
}
