package com.platform.orgservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Duration;
import java.time.Instant;

/**
 * 발송 큐의 한 통. 큐이자 로그다 — 보낸 뒤에도 30일 남겨 두어 "그 메일 갔나요"에 답할 수 있게 한다.
 *
 * <p>호출측 트랜잭션 안에서 SMTP를 때리지 않는 이유: 초대 생성이 SMTP 지연에 묶이고, 발송 실패가
 * 초대 자체를 롤백시킨다. 여기에 행이 커밋되면 초대는 이미 원장에 있고 배달만 남는다(outbox 패턴).
 */
@Entity
@Table(name = "mail_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MailOutbox {

    /** 자동 재시도 상한. 이걸 넘으면 FAILED로 눕히고 사람이 로그에서 다시 민다. */
    public static final int MAX_ATTEMPTS = 5;

    /** 지수 백오프의 첫 간격 — 30초, 1분, 2분, 4분. 임시 장애는 대개 이 안에서 걷힌다. */
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "to_address", nullable = false, length = 320)
    private String toAddress;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(name = "body_text", nullable = false, columnDefinition = "text")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    @Column(nullable = false, length = 32)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MailStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public static MailOutbox queued(String toAddress, String subject, String bodyText, String bodyHtml,
                                    String source, Instant now) {
        MailOutbox m = new MailOutbox();
        m.toAddress = toAddress.trim();
        m.subject = subject;
        m.bodyText = bodyText == null ? "" : bodyText;
        m.bodyHtml = (bodyHtml == null || bodyHtml.isBlank()) ? null : bodyHtml;
        m.source = source;
        m.status = MailStatus.PENDING;
        m.attempts = 0;
        m.nextAttemptAt = now;
        return m;
    }

    public void markSent(Instant now) {
        this.attempts++;
        this.status = MailStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
    }

    /**
     * 한 번 실패했다. 남은 시도가 있으면 백오프를 걸어 다시 대기하고, 다 썼으면 FAILED로 눕힌다.
     * 실패 문구는 그대로 남긴다 — SMTP가 알려 준 이유가 관리자에게 가장 쓸모 있는 정보다.
     */
    public void markAttemptFailed(String error, Instant now) {
        this.attempts++;
        this.lastError = truncate(error);
        if (attempts >= MAX_ATTEMPTS) {
            this.status = MailStatus.FAILED;
            return;
        }
        this.status = MailStatus.PENDING;
        this.nextAttemptAt = now.plus(BACKOFF_BASE.multipliedBy(1L << (attempts - 1)));
    }

    /** 관리자가 로그에서 다시 보낸다 — 시도 횟수를 0으로 되돌려 백오프 사다리도 처음부터 탄다. */
    public void requeue(Instant now) {
        this.status = MailStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
