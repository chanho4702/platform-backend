package com.platform.orgservice.invitation;

import com.jayway.jsonpath.JsonPath;
import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.Invitation;
import com.platform.orgservice.domain.InvitationStatus;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.InvitationRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 초대 목록·재발송·철회·만료. */
@SpringBootTest
@ActiveProfiles("test")
class InvitationAdminApiTest {

    static final long ADMIN_ID = 100L;

    @Autowired WebApplicationContext context;
    @Autowired InvitationRepository invitations;
    @Autowired InvitationService service;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired GrantEntryRepository grants;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberEvents.deleteAll();
        invitations.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
    }

    private long invite(String email) throws Exception {
        String created = mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"" + email + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(created).read("$[0].id", Long.class);
    }

    /**
     * 목록에는 링크가 없다 — 토큰 원문을 저장하지 않기 때문이다. 링크가 다시 필요하면 재발송이
     * 새 토큰을 만들고, 이전 링크는 그 순간 죽는다.
     */
    @Test
    void 목록은_링크를_주지_않고_재발송이_새_링크를_준다() throws Exception {
        invite("a@test.com");

        mvc.perform(get("/api/org/invitations?status=PENDING&q=a@").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].email").value("a@test.com"))
                .andExpect(jsonPath("$.items[0].inviteUrl").doesNotExist());

        String before = invitations.findAll().getFirst().getTokenHash();
        long id = invitations.findAll().getFirst().getId();

        mvc.perform(post("/api/org/invitations/" + id + "/resend?mail=false").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteUrl").isNotEmpty())
                .andExpect(jsonPath("$.mailSent").value(false));

        assertThat(invitations.findById(id).orElseThrow().getTokenHash()).isNotEqualTo(before);
    }

    @Test
    void 철회하면_REVOKED가_되고_두_번은_409다() throws Exception {
        long id = invite("b@test.com");

        mvc.perform(delete("/api/org/invitations/" + id).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNoContent());
        assertThat(invitations.findById(id).orElseThrow().getStatus()).isEqualTo(InvitationStatus.REVOKED);

        mvc.perform(delete("/api/org/invitations/" + id).with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("대기 중인 초대만 철회할 수 있습니다"));
    }

    /** 아무도 쓰지 않은 초대가 영영 "대기 중"으로 보이면 목록을 믿을 수 없다. */
    @Test
    void 만료_스케줄러가_지난_초대를_눕힌다() {
        Invitation stale = invitations.save(Invitation.of("old@test.com",
                InvitationTokens.hash(InvitationTokens.newToken()), ADMIN_ID, null,
                Instant.now().minus(1, ChronoUnit.DAYS)));

        assertThat(service.expireDue(Instant.now())).isEqualTo(1);
        assertThat(invitations.findById(stale.getId()).orElseThrow().getStatus())
                .isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    void 초대_이력은_전역_관리자만_본다() throws Exception {
        long id = invite("c@test.com");
        active(members, 700L, "Nobody");

        mvc.perform(get("/api/org/invitations/" + id + "/events").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("INVITED"));

        mvc.perform(get("/api/org/invitations/" + id + "/events").with(asUser(700L, "Nobody")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 이메일_형식이_틀리면_400이다() throws Exception {
        mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"골뱅이없음\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이메일 형식이 올바르지 않습니다: 골뱅이없음"));
    }
}
