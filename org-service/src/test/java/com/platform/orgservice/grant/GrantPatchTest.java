package com.platform.orgservice.grant;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.repository.GrantAuditRepository;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 권한 역할 제자리 변경과 마지막 전역 관리자 보호.
 *
 * <p>삭제 후 재생성 대신 PATCH를 쓰는 이유는 id가 유지돼야 화면의 행이 사라졌다 나타나지 않고,
 * 감사 기록이 "회수하고 다시 줬다"가 아니라 "바꿨다"로 읽히기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class GrantPatchTest {

    static final long ADMIN_ID = 100L;
    static final long USER_ID = 200L;

    @Autowired WebApplicationContext context;
    @Autowired GrantEntryRepository grants;
    @Autowired GrantAuditRepository audits;
    @Autowired MemberRepository members;
    @Autowired TeamRepository teams;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        audits.deleteAll();
        grants.deleteAll();
        teams.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        active(members, USER_ID, "Bob");
    }

    @Test
    void 역할을_제자리에서_바꾸고_감사에_CHANGED로_남긴다() throws Exception {
        long id = grants.save(GrantEntry.of(SubjectType.USER, USER_ID, ResourceKind.SPACE, "sp-1",
                GrantRole.VIEWER)).getId();

        mvc.perform(patch("/api/org/grants/" + id).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.role").value("EDITOR"));

        assertThat(grants.findById(id).orElseThrow().getRole()).isEqualTo(GrantRole.EDITOR);
        assertThat(grants.findById(id).orElseThrow().getUpdatedBy()).isEqualTo(ADMIN_ID);
        assertThat(audits.findAll()).anyMatch(a -> a.getAction() == com.platform.orgservice.domain.GrantAudit.Action.CHANGED);
    }

    @Test
    void 마지막_전역_관리자의_강등은_409다() throws Exception {
        long id = grants.findBySubjectTypeAndSubjectIdAndResourceTypeAndResourceId(
                SubjectType.USER, ADMIN_ID, ResourceKind.GLOBAL, "").orElseThrow().getId();

        mvc.perform(patch("/api/org/grants/" + id).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("마지막 전역 관리자는 내릴 수 없습니다"));

        mvc.perform(delete("/api/org/grants/" + id).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("마지막 전역 관리자는 내릴 수 없습니다"));

        assertThat(grants.findById(id).orElseThrow().getRole()).isEqualTo(GrantRole.ADMIN);
    }

    @Test
    void 관리자가_둘이면_한_명은_내릴_수_있다() throws Exception {
        active(members, 101L, "Admin2");
        long id = grants.save(GrantEntry.globalAdmin(101L)).getId();

        mvc.perform(patch("/api/org/grants/" + id).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());
    }

    /**
     * 목록이 이름을 함께 주지 않으면 권한 화면이 멤버·팀 디렉터리를 통째로 다시 받아 와야 한다.
     * 대상이 지워진 뒤에도 행은 읽혀야 하므로 못 찾으면 id로 대신한다.
     */
    @Test
    void grant_목록은_id와_대상_이름을_준다() throws Exception {
        long teamId = teams.save(com.platform.orgservice.domain.Team.of("플랫폼팀", null)).getId();
        long userGrantId = grants.save(GrantEntry.of(SubjectType.USER, USER_ID, ResourceKind.SPACE, "sp-1",
                GrantRole.VIEWER)).getId();
        grants.save(GrantEntry.of(SubjectType.TEAM, teamId, ResourceKind.SPACE, "sp-1", GrantRole.EDITOR));
        grants.save(GrantEntry.of(SubjectType.USER, 99999L, ResourceKind.SPACE, "sp-1", GrantRole.VIEWER));

        mvc.perform(get("/api/org/grants?resourceType=SPACE&resourceId=sp-1").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + userGrantId + ")].subjectName").value("Bob"))
                .andExpect(jsonPath("$[?(@.subjectType=='TEAM')].subjectName").value("플랫폼팀"))
                .andExpect(jsonPath("$[?(@.subjectId==99999)].subjectName").value("사용자 #99999"));
    }

    @Test
    void 전역_역할_목록도_이름을_준다() throws Exception {
        mvc.perform(get("/api/org/grants?resourceType=GLOBAL").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].subjectName").value("Admin"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"));
    }

    @Test
    void 생성과_역할_변경_응답에도_이름이_실린다() throws Exception {
        String created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/org/grants").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"USER\",\"subjectId\":" + USER_ID
                                + ",\"resourceType\":\"SPACE\",\"resourceId\":\"sp-2\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectName").value("Bob"))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(created).read("$.id", Long.class);

        mvc.perform(patch("/api/org/grants/" + id).with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectName").value("Bob"));
    }

    @Test
    void 그_리소스의_관리자가_아니면_403이다() throws Exception {
        long id = grants.save(GrantEntry.of(SubjectType.USER, USER_ID, ResourceKind.SPACE, "sp-1",
                GrantRole.VIEWER)).getId();

        mvc.perform(patch("/api/org/grants/" + id).with(asUser(USER_ID, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }
}
