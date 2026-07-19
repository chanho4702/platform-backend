package com.platform.orgservice.me;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.GrantRole;
import com.platform.orgservice.domain.ResourceKind;
import com.platform.orgservice.domain.SubjectType;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class MeControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired GrantEntryRepository grants;
    @Autowired MemberRepository members;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        grants.deleteAll();
        members.deleteAll();
    }

    @Test
    void 무토큰은_401() throws Exception {
        mvc.perform(get("/api/org/me/permissions")).andExpect(status().isUnauthorized());
    }

    @Test
    void 내_grant_목록을_반환한다() throws Exception {
        grants.save(GrantEntry.of(SubjectType.USER, 1L, ResourceKind.SPACE, "sp-1", GrantRole.EDITOR));

        mvc.perform(get("/api/org/me/permissions").with(asUser(1L, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resourceType").value("SPACE"))
                .andExpect(jsonPath("$[0].resourceId").value("sp-1"))
                .andExpect(jsonPath("$[0].role").value("EDITOR"));
    }

    @Test
    void 인증_요청이_지나가면_member가_JIT_미러링된다() throws Exception {
        mvc.perform(get("/api/org/me/permissions").with(asUser(7L, "Bob"))).andExpect(status().isOk());

        assertThat(members.findById(7L)).isPresent()
                .get().satisfies(m -> assertThat(m.getDisplayName()).isEqualTo("Bob"));
    }
}
