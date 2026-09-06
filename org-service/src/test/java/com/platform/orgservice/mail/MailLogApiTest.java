package com.platform.orgservice.mail;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.MailOutbox;
import com.platform.orgservice.domain.MailSetting;
import com.platform.orgservice.domain.MailStatus;
import com.platform.orgservice.domain.MailTls;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static com.platform.orgservice.TestAuth.active;
import static com.platform.orgservice.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리 화면이 쓰는 것 — 테스트 발송, 발송 로그, 실패분 다시 보내기. */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeMailConfig.class)
class MailLogApiTest {

    static final long ADMIN_ID = 100L;

    @Autowired WebApplicationContext context;
    @Autowired GrantEntryRepository grants;
    @Autowired MemberRepository members;
    @Autowired MailOutboxRepository outbox;
    @Autowired MailSettingRepository settings;
    @Autowired MailOutboxService service;
    @Autowired FakeMailConfig.FakeMailbox mailbox;
    @Autowired JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        outbox.deleteAll();
        settings.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        mailbox.reset();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        settings.saveAndFlush(MailSetting.seed(true, "smtp.test", 587, null, null,
                MailTls.STARTTLS, "no-reply@test.com", "플랫폼"));
    }

    // ---------------------------------------------------------------- 테스트 발송

    /** 본문이 없으면 요청한 관리자 본인에게 — TestAuth의 jwt는 email 클레임을 싣는다. */
    @Test
    void 테스트_발송은_기본으로_요청자에게_간다() throws Exception {
        mvc.perform(post("/api/org/settings/mail/test").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(mailbox.count()).isEqualTo(1);
        MailOutbox row = outbox.findAll().getFirst();
        assertThat(row.getToAddress()).isEqualTo("admin@test.com");
        assertThat(row.getSource()).isEqualTo("test");
        assertThat(row.getStatus()).isEqualTo(MailStatus.SENT);
    }

    @Test
    void 받는_주소를_주면_그리로_간다() throws Exception {
        mvc.perform(post("/api/org/settings/mail/test").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"someone@test.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertThat(outbox.findAll().getFirst().getToAddress()).isEqualTo("someone@test.com");
    }

    /** 실패해도 200이다 — 화면이 SMTP 문구를 그대로 보여 줘야 관리자가 무엇이 틀렸는지 안다. */
    @Test
    void 실패해도_200이고_SMTP_문구가_그대로_담긴다() throws Exception {
        mailbox.failure = "Couldn't connect to host, port: smtp.test, 587; timeout -1";

        mvc.perform(post("/api/org/settings/mail/test").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Couldn't connect to host")));

        assertThat(outbox.findAll().getFirst().getLastError()).contains("Couldn't connect to host");
    }

    @Test
    void 메일이_꺼져_있으면_테스트_발송이_이유를_말한다() throws Exception {
        settings.saveAndFlush(MailSetting.seed(false, null, 587, null, null,
                MailTls.STARTTLS, null, null));

        mvc.perform(post("/api/org/settings/mail/test").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(
                        "메일 발송이 꺼져 있거나 호스트·보내는 주소가 비어 있습니다"));

        assertThat(outbox.count()).isZero();
    }

    // ---------------------------------------------------------------- 로그

    @Test
    void 로그는_최신순이고_상태로_거를_수_있다() throws Exception {
        service.enqueue(List.of("sent@test.com"), "보낸 것", "본문", null, "wiki");
        service.drainOnce();
        service.enqueue(List.of("pending@test.com"), "대기 중", "본문", null, "alm");

        mvc.perform(get("/api/org/settings/mail/log").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].to").value("pending@test.com"))
                .andExpect(jsonPath("$.items[0].source").value("alm"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[1].status").value("SENT"))
                .andExpect(jsonPath("$.items[1].attempts").value(1));

        mvc.perform(get("/api/org/settings/mail/log?status=SENT").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].to").value("sent@test.com"));
    }

    @Test
    void 알_수_없는_상태_필터는_400이다() throws Exception {
        mvc.perform(get("/api/org/settings/mail/log?status=아무거나").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("상태는 PENDING·SENT·FAILED 중 하나여야 합니다"));
    }

    /** 본문은 목록에 담지 않는다 — 로그 화면이 곧 사서함이 되면 안 된다. */
    @Test
    void 로그에는_본문이_담기지_않는다() throws Exception {
        service.enqueue(List.of("a@test.com"), "제목", "비밀 본문", "<p>비밀 본문</p>", "wiki");

        mvc.perform(get("/api/org/settings/mail/log").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bodyText").doesNotExist())
                .andExpect(jsonPath("$.items[0].bodyHtml").doesNotExist());
    }

    // ---------------------------------------------------------------- 다시 보내기

    @Test
    void 실패한_발송만_다시_보낼_수_있다() throws Exception {
        service.enqueue(List.of("a@test.com"), "제목", "본문", null, "wiki");
        mailbox.failure = "550 relay denied";
        long id = outbox.findAll().getFirst().getId();
        for (int i = 0; i < MailOutbox.MAX_ATTEMPTS; i++) {
            jdbc.update("update mail_outbox set next_attempt_at = ? where id = ?",
                    Timestamp.from(Instant.now().minusSeconds(1)), id);
            service.drainOnce();
        }
        assertThat(outbox.findById(id).orElseThrow().getStatus()).isEqualTo(MailStatus.FAILED);

        mvc.perform(post("/api/org/settings/mail/log/" + id + "/retry").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNoContent());

        MailOutbox requeued = outbox.findById(id).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(requeued.getAttempts()).isZero();   // 백오프 사다리도 처음부터

        mailbox.failure = null;
        assertThat(service.drainOnce()).isEqualTo(1);
    }

    /** 이미 보낸 것을 다시 보내면 같은 메일이 두 번 간다. */
    @Test
    void 보낸_것을_다시_보내려_하면_409다() throws Exception {
        service.enqueue(List.of("a@test.com"), "제목", "본문", null, "wiki");
        service.drainOnce();
        long id = outbox.findAll().getFirst().getId();

        mvc.perform(post("/api/org/settings/mail/log/" + id + "/retry").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("실패한 발송만 다시 보낼 수 있습니다"));
    }

    @Test
    void 없는_기록을_다시_보내려_하면_404다() throws Exception {
        mvc.perform(post("/api/org/settings/mail/log/9999/retry").with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("발송 기록을 찾을 수 없습니다"));
    }
}
