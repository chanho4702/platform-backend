package com.platform.orgservice.mail;

import com.platform.orgservice.domain.MailSetting;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * 실제 SMTP 없이 발송 경로 전체를 돌린다.
 *
 * <p>{@link JavaMailSenderFactory}만 갈아 끼우는 이유는, 큐·재시도·백오프·로그가 검증 대상이고
 * "소켓이 열리는가"는 아니기 때문이다. 인메모리 SMTP를 붙이면 의존성이 늘고, 임의의 SMTP 오류 문구를
 * 주입해 그것이 로그에 그대로 실리는지 보는 일이 오히려 어려워진다.
 */
@TestConfiguration
public class FakeMailConfig {

    /** 보낸 것을 모으고, 필요하면 다음 발송을 실패시킨다. */
    public static class FakeMailbox {

        public final List<MimeMessage> sent = new ArrayList<>();

        /** null이 아니면 발송이 이 문구로 실패한다 — SMTP 오류가 그대로 행에 실리는지 보려고. */
        public volatile String failure;

        /** null이 아니면 받는 주소에 이 문자열이 들어간 통만 실패한다(배치 안 부분 실패 재현). */
        public volatile String failOnlyFor;

        public void reset() {
            sent.clear();
            failure = null;
            failOnlyFor = null;
        }

        public int count() { return sent.size(); }

        boolean shouldFail(MimeMessage message) {
            if (failure == null) return false;
            return failOnlyFor == null || firstRecipient(message).contains(failOnlyFor);
        }

        private static String firstRecipient(MimeMessage message) {
            try {
                Address[] to = message.getRecipients(Message.RecipientType.TO);
                return (to == null || to.length == 0) ? "" : to[0].toString();
            } catch (Exception e) {
                return "";
            }
        }
    }

    @Bean
    FakeMailbox fakeMailbox() { return new FakeMailbox(); }

    @Bean
    @Primary
    JavaMailSenderFactory fakeJavaMailSenderFactory(FakeMailbox mailbox) {
        return (MailSetting setting, String password) -> new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage... messages) {
                for (MimeMessage message : messages) {
                    if (mailbox.shouldFail(message)) throw new MailSendException(mailbox.failure);
                }
                mailbox.sent.addAll(List.of(messages));
            }
        };
    }
}
