package com.platform.orgservice.mail;

import com.platform.orgservice.domain.MailOutbox;
import com.platform.orgservice.domain.MailSetting;
import com.platform.orgservice.domain.MailStatus;
import com.platform.orgservice.domain.MailTls;
import com.platform.orgservice.repository.MailOutboxRepository;
import com.platform.orgservice.repository.MailSettingRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큐를 실제로 비우는 경로 — 성공·재시도·백오프·FAILED·정리.
 *
 * <p>워커의 5초 폴링은 테스트 프로필에서 꺼 두고({@code poll-ms: 3600000}) {@code drainOnce()}를 직접 부른다.
 * 스케줄러가 끼어들면 시도 횟수가 흔들려 무엇을 검증하는 테스트인지 알 수 없게 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeMailConfig.class)
class MailOutboxWorkerTest {

    @Autowired MailOutboxRepository outbox;
    @Autowired MailSettingRepository settings;
    @Autowired MailOutboxService service;
    @Autowired FakeMailConfig.FakeMailbox mailbox;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        outbox.deleteAll();
        settings.deleteAll();
        mailbox.reset();
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

    /** @return 방금 넣은 행의 id. IDENTITY라 가장 큰 값이 방금 것이다. */
    private long queue(String to) {
        service.enqueue(List.of(to), "제목", "본문", null, "wiki");
        return outbox.findAll().stream().mapToLong(MailOutbox::getId).max().orElseThrow();
    }

    /** 백오프로 미뤄진 행을 지금 집을 수 있게 당긴다 — 시계를 못 돌리니 데이터를 당긴다. */
    private void makeDue(long id) {
        jdbc.update("update mail_outbox set next_attempt_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), id);
    }

    @Test
    void 큐에_넣은_메일을_보내고_SENT로_남긴다() {
        long id = queue("a@test.com");

        int sent = service.drainOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(mailbox.count()).isEqualTo(1);
        MailOutbox row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MailStatus.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getSource()).isEqualTo("wiki");
    }

    /** 이미 보낸 것을 다시 집으면 같은 메일이 두 번 간다. */
    @Test
    void 보낸_메일은_다시_집지_않는다() {
        queue("a@test.com");
        service.drainOnce();

        assertThat(service.drainOnce()).isZero();
        assertThat(mailbox.count()).isEqualTo(1);
    }

    @Test
    void 실패하면_사유를_남기고_백오프로_미룬다() {
        long id = queue("a@test.com");
        mailbox.failure = "Couldn't connect to host, port: smtp.test, 587";

        assertThat(service.drainOnce()).isZero();

        MailOutbox row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("Couldn't connect to host");
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now());

        // 백오프가 걸려 있는 동안에는 집지 않는다 — 죽은 서버를 5초마다 두드리지 않는다.
        assertThat(service.drainOnce()).isZero();
        assertThat(outbox.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void 다섯_번_실패하면_FAILED로_눕는다() {
        long id = queue("a@test.com");
        mailbox.failure = "550 relay denied";

        for (int i = 0; i < MailOutbox.MAX_ATTEMPTS; i++) {
            makeDue(id);
            service.drainOnce();
        }

        MailOutbox row = outbox.findById(id).orElseThrow();
        assertThat(row.getAttempts()).isEqualTo(MailOutbox.MAX_ATTEMPTS);
        assertThat(row.getStatus()).isEqualTo(MailStatus.FAILED);
        assertThat(row.getLastError()).contains("550 relay denied");

        // FAILED는 워커가 더는 건드리지 않는다 — 사람이 로그에서 다시 민다.
        makeDue(id);
        assertThat(service.drainOnce()).isZero();
        assertThat(outbox.findById(id).orElseThrow().getAttempts()).isEqualTo(MailOutbox.MAX_ATTEMPTS);
    }

    /** 한 통이 실패해도 같은 배치의 나머지는 나가야 한다 — 롤백되면 시도 횟수가 안 늘어 영원히 반복된다. */
    @Test
    void 한_통이_실패해도_배치의_나머지는_나간다() {
        long bad = queue("bad@test.com");
        long good = queue("good@test.com");
        mailbox.failure = "550 no such user";
        mailbox.failOnlyFor = "bad@test.com";

        int sent = service.drainOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(outbox.findById(good).orElseThrow().getStatus()).isEqualTo(MailStatus.SENT);
        MailOutbox failed = outbox.findById(bad).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("550 no such user");
    }

    @Test
    void 메일이_꺼져_있으면_큐에_넣지_않고_disabled로_답한다() {
        disableMail();

        MailOutboxService.EnqueueResult result =
                service.enqueue(List.of("a@test.com"), "제목", "본문", null, "alm");

        assertThat(result.disabled()).isTrue();
        assertThat(result.accepted()).isZero();
        assertThat(outbox.count()).isZero();
    }

    /** 켜진 동안 들어온 것을 끄고 나서 집으면 — FAILED로 눕히지 않는다. 다시 켜면 그대로 나가야 한다. */
    @Test
    void 큐에_있는데_메일이_꺼지면_PENDING으로_남는다() {
        long id = queue("a@test.com");
        disableMail();

        assertThat(service.drainOnce()).isZero();

        MailOutbox row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(row.getLastError()).isEqualTo("메일 발송이 꺼져 있습니다");
    }

    @Test
    void 빈_주소는_버리고_중복은_하나로_친다() {
        MailOutboxService.EnqueueResult result = service.enqueue(
                Arrays.asList("a@test.com", " ", null, "A@test.com", "b@test.com"),
                "제목", "본문", null, "wiki");

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(outbox.findAll()).extracting(MailOutbox::getToAddress)
                .containsExactlyInAnyOrder("a@test.com", "b@test.com");
    }

    @Test
    void 배치_상한만큼만_한_번에_집는다() {
        for (int i = 0; i < 25; i++) queue("user" + i + "@test.com");

        assertThat(service.drainOnce()).isEqualTo(20);   // platform.org.mail.outbox.batch-size 기본값
        assertThat(service.drainOnce()).isEqualTo(5);
    }

    /** 차례가 오지 않은 것은 두고 간다 — 백오프가 걸린 행을 매 폴링마다 다시 때리면 백오프가 없는 것과 같다. */
    @Test
    void 차례가_된_것만_집는다() {
        long due = queue("due@test.com");
        long later = queue("later@test.com");
        jdbc.update("update mail_outbox set next_attempt_at = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(600)), later);

        assertThat(service.drainOnce()).isEqualTo(1);

        assertThat(outbox.findById(due).orElseThrow().getStatus()).isEqualTo(MailStatus.SENT);
        assertThat(outbox.findById(later).orElseThrow().getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(outbox.findById(later).orElseThrow().getAttempts()).isZero();
    }

    @Test
    void 오래된_종결분만_정리한다() {
        long sent = queue("sent@test.com");
        service.drainOnce();
        long pending = queue("pending@test.com");
        jdbc.update("update mail_outbox set created_at = ?",
                Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)));

        assertThat(service.cleanup()).isEqualTo(1);

        assertThat(outbox.findById(sent)).isEmpty();
        assertThat(outbox.findById(pending)).isPresent();   // 아직 배달할 것이 남았다는 뜻이다
    }

    /**
     * {@code FOR UPDATE SKIP LOCKED} 계약이 사라지지 않았는지.
     *
     * <p>동시 실행으로 검증하지 않는 이유: 테스트는 H2에서 도는데 H2에는 {@code SKIP LOCKED} 문법이 없어
     * Hibernate가 평범한 {@code for update}로 내린다(그래서 두 번째 트랜잭션은 건너뛰는 대신 <b>막힌다</b>).
     * PostgreSQL에서만 나타나는 동작이라, 여기서는 그 SQL을 만들게 하는 선언이 붙어 있는지를 고정한다.
     */
    @Test
    void 큐를_집는_질의는_잠금과_SKIP_LOCKED를_선언한다() throws Exception {
        Method claim = MailOutboxRepository.class.getMethod("claimDue", Instant.class, Pageable.class);

        Lock lock = claim.getAnnotation(Lock.class);
        QueryHints hints = claim.getAnnotation(QueryHints.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(hints).isNotNull();
        assertThat(hints.value()).anySatisfy(hint -> {
            assertThat(hint.name()).isEqualTo("jakarta.persistence.lock.timeout");
            assertThat(hint.value()).isEqualTo("-2");   // Hibernate의 SKIP_LOCKED
        });
    }
}
