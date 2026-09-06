package com.platform.orgservice.mail;

import com.platform.orgservice.domain.MailOutbox;
import com.platform.orgservice.domain.MailSetting;
import com.platform.orgservice.domain.MailTls;
import com.platform.orgservice.repository.MailOutboxRepository;
import com.platform.orgservice.repository.MailSettingRepository;
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

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서비스 간 발송 경로({@code /internal/org/mail}). wiki·alm이 JavaMail을 버리고 여기로 넘긴다 —
 * 인증은 {@code X-Internal-Token} 하나뿐이고, 토큰이 없거나 다르면 403이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeMailConfig.class)
class InternalMailApiTest {

    static final String TOKEN_HEADER = "X-Internal-Token";
    static final String TOKEN = "test-internal-token";

    @Autowired WebApplicationContext context;
    @Autowired MailOutboxRepository outbox;
    @Autowired MailSettingRepository settings;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        outbox.deleteAll();
        settings.deleteAll();
        enableMail();
    }

    private void enableMail() {
        settings.saveAndFlush(MailSetting.seed(true, "smtp.test", 587, null, null,
                MailTls.STARTTLS, "no-reply@test.com", "플랫폼"));
    }

    private void disableMail() {
        settings.saveAndFlush(MailSetting.seed(false, null, 587, null, null,
                MailTls.STARTTLS, null, null));
    }

    @Test
    void 토큰이_없거나_틀리면_403이다() throws Exception {
        mvc.perform(post("/internal/org/mail").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":["a@test.com"],"subject":"제목","text":"본문","source":"wiki"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("내부 API 토큰이 유효하지 않습니다"));

        mvc.perform(get("/internal/org/mail/status").header(TOKEN_HEADER, "틀린값"))
                .andExpect(status().isForbidden());

        assertThat(outbox.count()).isZero();
    }

    @Test
    void 유효한_토큰이면_큐에_넣고_202를_돌려준다() throws Exception {
        mvc.perform(post("/internal/org/mail").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":["a@test.com","b@test.com"],"subject":"위키 알림",
                                 "text":"본문","html":"<p>본문</p>","source":"wiki"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.disabled").value(false));

        assertThat(outbox.findAll()).extracting(MailOutbox::getSource).containsOnly("wiki");
        assertThat(outbox.findAll()).allSatisfy(m -> assertThat(m.getBodyHtml()).isEqualTo("<p>본문</p>"));
    }

    /** 소비자는 이것을 오류가 아니라 "메일이 꺼져 있음"으로 다뤄야 한다 — 화면이 다른 안내를 낸다. */
    @Test
    void 메일이_꺼져_있으면_disabled로_답하고_큐는_비어_있다() throws Exception {
        disableMail();

        mvc.perform(post("/internal/org/mail").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":["a@test.com"],"subject":"제목","text":"본문","source":"alm"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.disabled").value(true));

        assertThat(outbox.count()).isZero();
    }

    @Test
    void 상태_조회는_실제로_보낼_수_있는지를_알려준다() throws Exception {
        mvc.perform(get("/internal/org/mail/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        disableMail();

        mvc.perform(get("/internal/org/mail/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    /** 켜 두었지만 호스트가 비면 "켜졌다"고 말하지 않는다 — 소비자가 헛되이 큐에 넣게 된다. */
    @Test
    void 켜져_있어도_호스트가_비면_꺼진_것으로_본다() throws Exception {
        settings.saveAndFlush(MailSetting.seed(true, null, 587, null, null,
                MailTls.STARTTLS, "no-reply@test.com", null));

        mvc.perform(get("/internal/org/mail/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void 받는_주소는_100개까지다() throws Exception {
        String tooMany = IntStream.range(0, 101)
                .mapToObj(i -> "\"user" + i + "@test.com\"")
                .collect(Collectors.joining(","));

        mvc.perform(post("/internal/org/mail").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":[" + tooMany + "],\"subject\":\"제목\",\"text\":\"본문\",\"source\":\"wiki\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("받는 주소는 100개까지입니다"));

        assertThat(outbox.count()).isZero();
    }

    @Test
    void 제목_본문_출처가_없으면_400이다() throws Exception {
        mvc.perform(post("/internal/org/mail").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":["a@test.com"],"subject":"","text":"본문","source":"wiki"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("제목이 필요합니다"));

        mvc.perform(post("/internal/org/mail").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":[],"subject":"제목","text":"본문","source":"wiki"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("받는 주소가 필요합니다"));
    }

    /** 소비자가 구독자 목록을 합쳐 넘기면 같은 사람이 두 번 들어오는 일이 흔하다. */
    @Test
    void 빈_주소는_버리고_중복은_하나로_친다() throws Exception {
        mvc.perform(post("/internal/org/mail").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"to":["a@test.com","  ","A@test.com"],"subject":"제목",
                                 "text":"본문","source":"alm"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));

        assertThat(outbox.count()).isEqualTo(1);
    }
}
