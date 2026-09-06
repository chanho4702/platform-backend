package com.platform.orgservice.mail;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.MailOutboxRepository;
import com.platform.orgservice.repository.MailSettingRepository;
import com.platform.orgservice.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 메일 설정 관리 API. <b>암호화 키가 없는</b> 기본 배치다 — 키 없이도 호스트·발신자는 저장되고
 * 비밀번호만 거부되는지가 여기서 걸린다(키가 있는 경우는 {@link MailSettingEncryptionTest}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeMailConfig.class)
class MailSettingApiTest {

    static final long ADMIN_ID = 100L;
    static final long USER_ID = 101L;

    @Autowired WebApplicationContext context;
    @Autowired GrantEntryRepository grants;
    @Autowired MemberRepository members;
    @Autowired MailSettingRepository settings;
    @Autowired MailOutboxRepository outbox;
    @Autowired MailSettingService settingService;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        outbox.deleteAll();
        settings.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        active(members, USER_ID, "User");
        settingService.ensureSeeded();
    }

    /** 행이 없으면 MAIL_SEED_*로 만든다. 테스트 기본값은 시드가 비어 있으므로 꺼진 채로 나온다. */
    @Test
    void 행이_없으면_시드해_꺼진_설정을_돌려준다() throws Exception {
        settings.deleteAll();

        mvc.perform(get("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.mode").value("none"))
                .andExpect(jsonPath("$.port").value(587))
                .andExpect(jsonPath("$.tls").value("STARTTLS"))
                .andExpect(jsonPath("$.passwordSet").value(false));

        assertThat(settings.findAll()).hasSize(1);
    }

    @Test
    void 저장한_값이_그대로_돌아오고_updatedBy가_남는다() throws Exception {
        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"host":"mail-relay","port":25,"username":"",
                                 "tls":"NONE","fromAddress":"no-reply@test.com","fromName":"플랫폼"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.host").value("mail-relay"))
                .andExpect(jsonPath("$.port").value(25))
                .andExpect(jsonPath("$.tls").value("NONE"))
                .andExpect(jsonPath("$.fromAddress").value("no-reply@test.com"))
                .andExpect(jsonPath("$.fromName").value("플랫폼"))
                .andExpect(jsonPath("$.updatedBy").value(ADMIN_ID));
    }

    /** 켜 두고 호스트를 비우면 SMTP 단계에서야 실패해 원인이 흐릿해진다 — 저장 시점에 막는다. */
    @Test
    void 켜면_호스트와_보내는_주소가_필수다() throws Exception {
        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"host":"","port":25,"tls":"NONE","fromAddress":"a@test.com"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("메일을 사용하려면 호스트가 필요합니다"));

        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"host":"mail","port":25,"tls":"NONE","fromAddress":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("메일을 사용하려면 보내는 주소가 필요합니다"));

        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"host":"mail","port":25,"tls":"NONE","fromAddress":"주소아님"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("보내는 주소가 이메일 형식이 아닙니다"));
    }

    /** 끄는 것은 언제나 된다 — 끄려고 값을 다 지워야 한다면 끄기가 번거롭다. */
    @Test
    void 끌_때는_빈_값이어도_저장된다() throws Exception {
        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false,"host":"","port":587,"tls":"STARTTLS","fromAddress":""}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void 포트와_전송보안은_허용된_값만_받는다() throws Exception {
        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false,"host":"mail","port":70000,"tls":"STARTTLS"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("포트는 1~65535 사이여야 합니다"));

        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false,"host":"mail","port":25,"tls":"MAYBE"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("전송 보안은 NONE·STARTTLS·SSL 중 하나여야 합니다"));
    }

    /** 키 없이 비밀번호를 받아 두면 "설정은 됐는데 사실 평문"인 상태가 조용히 운영으로 나간다. */
    @Test
    void 암호화_키가_없으면_비밀번호_저장을_거부한다() throws Exception {
        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"host":"mail","port":587,"username":"u","password":"p",
                                 "tls":"STARTTLS","fromAddress":"a@test.com"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("메일 비밀번호를 저장하려면 ORG_SETTINGS_ENC_KEY가 필요합니다"));

        // 거부됐으면 나머지 값도 남지 않아야 한다 — 반쯤 저장된 설정이 가장 설명하기 어렵다.
        assertThat(settings.findAll().getFirst().getHost()).isNull();
    }

    @Test
    void 전역_관리자가_아니면_전부_403이다() throws Exception {
        mvc.perform(get("/api/org/settings/mail").with(asUser(USER_ID, "User")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("GLOBAL ADMIN 권한이 필요합니다"));

        mvc.perform(put("/api/org/settings/mail").with(asUser(USER_ID, "User"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false,"host":"","port":587,"tls":"STARTTLS"}"""))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/org/settings/mail/log").with(asUser(USER_ID, "User")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/org/settings/mail/test").with(asUser(USER_ID, "User"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get("/api/org/settings/mail"))
                .andExpect(status().isUnauthorized());
    }
}
