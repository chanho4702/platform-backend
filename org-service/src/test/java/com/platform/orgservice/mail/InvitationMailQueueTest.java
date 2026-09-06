package com.platform.orgservice.mail;

import com.platform.orgservice.domain.GrantEntry;
import com.platform.orgservice.domain.MailOutbox;
import com.platform.orgservice.domain.MailSetting;
import com.platform.orgservice.domain.MailStatus;
import com.platform.orgservice.domain.MailTls;
import com.platform.orgservice.repository.GrantEntryRepository;
import com.platform.orgservice.repository.InvitationRepository;
import com.platform.orgservice.repository.MailOutboxRepository;
import com.platform.orgservice.repository.MailSettingRepository;
import com.platform.orgservice.repository.MemberEventRepository;
import com.platform.orgservice.repository.MemberRepository;
import com.platform.orgservice.repository.TeamMemberRepository;
import com.platform.orgservice.repository.TeamRepository;
import com.platform.orgservice.team.EveryoneTeamService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 초대 메일이 플랫폼 큐를 지나가는지.
 *
 * <p>예전에는 초대 트랜잭션 안에서 동기 발송했다 — 초대 생성이 SMTP 지연에 묶이고 발송 실패가
 * 초대를 롤백시켰다. 지금은 {@code mail_outbox}에 행을 남기고 워커가 보낸다.
 * 그래서 {@code mailSent}의 뜻도 "보냈다"가 아니라 <b>"큐에 넣었다"</b>로 바뀐다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeMailConfig.class)
class InvitationMailQueueTest {

    static final long ADMIN_ID = 100L;

    @Autowired WebApplicationContext context;
    @Autowired GrantEntryRepository grants;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository memberEvents;
    @Autowired InvitationRepository invitations;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired MailOutboxRepository outbox;
    @Autowired MailSettingRepository settings;
    @Autowired MailOutboxService mailService;
    @Autowired FakeMailConfig.FakeMailbox mailbox;
    @Autowired EveryoneTeamService everyone;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        outbox.deleteAll();
        settings.deleteAll();
        memberEvents.deleteAll();
        invitations.deleteAll();
        teamMembers.deleteAll();
        teams.deleteAll();
        grants.deleteAll();
        members.deleteAll();
        mailbox.reset();
        grants.save(GrantEntry.globalAdmin(ADMIN_ID));
        active(members, ADMIN_ID, "Admin");
        everyone.ensure();
    }

    private void enableMail() {
        settings.saveAndFlush(MailSetting.seed(true, "smtp.test", 587, null, null,
                MailTls.STARTTLS, "no-reply@test.com", "플랫폼"));
    }

    private void invite(String email) throws Exception {
        mvc.perform(post("/api/org/invitations").with(asUser(ADMIN_ID, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emails\":[\"" + email + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].mailSent").value(mailSentExpected()))
                .andExpect(jsonPath("$[0].inviteUrl").isNotEmpty());
    }

    private boolean mailSentExpected() {
        return settings.findById(MailSetting.SINGLETON_ID).map(MailSetting::sendable).orElse(false);
    }

    @Test
    void 초대_메일은_큐에_들어가고_워커가_보낸다() throws Exception {
        enableMail();

        invite("newbie@test.com");

        MailOutbox queued = outbox.findAll().getFirst();
        assertThat(queued.getToAddress()).isEqualTo("newbie@test.com");
        assertThat(queued.getSource()).isEqualTo("org");
        assertThat(queued.getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(queued.getSubject()).isEqualTo("[플랫폼] Admin님이 초대했습니다");
        assertThat(queued.getBodyText()).contains("/invite/");
        // 초대 응답이 돌아온 시점에는 아직 안 보냈다 — 발송은 워커의 일이다.
        assertThat(mailbox.count()).isZero();

        assertThat(mailService.drainOnce()).isEqualTo(1);
        assertThat(outbox.findAll().getFirst().getStatus()).isEqualTo(MailStatus.SENT);
    }

    /** 메일이 꺼져 있어도 초대는 만들어진다 — 화면이 링크 복사로 안내한다. */
    @Test
    void 메일이_꺼져_있으면_큐에도_넣지_않고_mailSent가_false다() throws Exception {
        invite("newbie@test.com");

        assertThat(outbox.count()).isZero();
        assertThat(invitations.count()).isEqualTo(1);
    }

    /** 재발송도 같은 경로를 탄다. {@code mail=false}면 큐에 넣지 않는다(링크만 다시 받는다). */
    @Test
    void 재발송은_mail_false면_큐에_넣지_않는다() throws Exception {
        enableMail();
        invite("newbie@test.com");
        long invitationId = invitations.findAll().getFirst().getId();
        outbox.deleteAll();

        mvc.perform(post("/api/org/invitations/" + invitationId + "/resend?mail=false")
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailSent").value(false));
        assertThat(outbox.count()).isZero();

        mvc.perform(post("/api/org/invitations/" + invitationId + "/resend?mail=true")
                        .with(asUser(ADMIN_ID, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailSent").value(true));
        assertThat(outbox.count()).isEqualTo(1);
    }
}
