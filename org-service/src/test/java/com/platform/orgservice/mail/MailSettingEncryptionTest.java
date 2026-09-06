package com.platform.orgservice.mail;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.MailSetting;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 암호화 키가 <b>있는</b> 배치. 비밀번호가 평문으로 남지 않고, 응답에 절대 실리지 않고,
 * 생략/빈 문자열/값의 세 갈래가 실제로 다르게 동작하는지를 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeMailConfig.class)
@TestPropertySource(properties =
        "platform.org.mail.enc-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
class MailSettingEncryptionTest {

    static final long ADMIN_ID = 100L;

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
        settingService.ensureSeeded();
    }

    private void save(String passwordJson) throws Exception {
        mvc.perform(put("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"host":"smtp.test","port":587,"username":"platform",
                                 %s"tls":"STARTTLS","fromAddress":"no-reply@test.com","fromName":"플랫폼"}"""
                                .formatted(passwordJson)))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀번호는_암호문으로_저장되고_응답에는_담기지_않는다() throws Exception {
        save("\"password\":\"s3cr3t\",");

        mvc.perform(get("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordEnc").doesNotExist());

        MailSetting stored = settings.findById(MailSetting.SINGLETON_ID).orElseThrow();
        assertThat(stored.getPasswordEnc()).isNotNull().doesNotContain("s3cr3t");
        assertThat(settingService.decryptPassword(stored)).isEqualTo("s3cr3t");
    }

    /** 화면은 "저장됨"만 보고 매번 다시 입력하지 않는다 — 생략은 유지여야 한다. */
    @Test
    void 비밀번호를_생략하면_그대로_유지된다() throws Exception {
        save("\"password\":\"s3cr3t\",");
        String encrypted = settings.findById(MailSetting.SINGLETON_ID).orElseThrow().getPasswordEnc();

        save("");   // password 필드 없음

        MailSetting after = settings.findById(MailSetting.SINGLETON_ID).orElseThrow();
        assertThat(after.getPasswordEnc()).isEqualTo(encrypted);
        assertThat(after.hasPassword()).isTrue();
    }

    @Test
    void 빈_문자열이면_비밀번호가_지워진다() throws Exception {
        save("\"password\":\"s3cr3t\",");

        save("\"password\":\"\",");

        assertThat(settings.findById(MailSetting.SINGLETON_ID).orElseThrow().hasPassword()).isFalse();
        mvc.perform(get("/api/org/settings/mail").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(jsonPath("$.passwordSet").value(false));
    }

    @Test
    void 새_값이면_교체된다() throws Exception {
        save("\"password\":\"old\",");

        save("\"password\":\"new\",");

        MailSetting stored = settings.findById(MailSetting.SINGLETON_ID).orElseThrow();
        assertThat(settingService.decryptPassword(stored)).isEqualTo("new");
    }
}
