package com.platform.orgservice.team;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.domain.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class TeamControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired GrantEntryRepository grants;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired MemberRepository members;
    MockMvc mvc;

    static final long ADMIN_ID = 100L;
    static final long USER_ID = 200L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
    }

    @Test
    void 팀_생성은_GLOBAL_ADMIN만_가능하다() throws Exception {
        mvc.perform(post("/api/org/teams").with(asUser(USER_ID, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dev\",\"description\":\"개발팀\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/org/teams").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dev\",\"description\":\"개발팀\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("dev"));
    }

    @Test
    void 팀_목록은_인증만_있으면_조회된다() throws Exception {
        mvc.perform(get("/api/org/teams").with(asUser(USER_ID, "Bob")))
                .andExpect(status().isOk());
    }

    @Test
    void 팀원_추가와_제거가_동작한다() throws Exception {
        members.save(Member.of(USER_ID, "Bob", null));
        String body = mvc.perform(post("/api/org/teams").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dev\",\"description\":null}"))
                .andReturn().getResponse().getContentAsString();
        long teamId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        mvc.perform(put("/api/org/teams/" + teamId + "/members/" + USER_ID + "?role=MEMBER")
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/org/teams/" + teamId + "/members/" + USER_ID)
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 없는_팀_수정은_404() throws Exception {
        mvc.perform(put("/api/org/teams/99999").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":null}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 중복_팀_이름은_400() throws Exception {
        mvc.perform(post("/api/org/teams").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dev\",\"description\":null}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/org/teams").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dev\",\"description\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 팀원_중복_추가는_멱등이다() throws Exception {
        members.save(Member.of(USER_ID, "Bob", null));
        String body = mvc.perform(post("/api/org/teams").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ops\",\"description\":null}"))
                .andReturn().getResponse().getContentAsString();
        long teamId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        mvc.perform(put("/api/org/teams/" + teamId + "/members/" + USER_ID + "?role=MEMBER")
                .with(asUser(ADMIN_ID, "Admin"))).andExpect(status().isOk());
        mvc.perform(put("/api/org/teams/" + teamId + "/members/" + USER_ID + "?role=MEMBER")
                .with(asUser(ADMIN_ID, "Admin"))).andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(teamMembers.findByTeamId(teamId)).hasSize(1);
    }
}
