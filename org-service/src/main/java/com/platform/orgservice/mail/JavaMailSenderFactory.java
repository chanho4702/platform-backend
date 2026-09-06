package com.platform.orgservice.mail;

import com.platform.orgservice.domain.MailSetting;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 설정 한 벌 → {@link JavaMailSender}.
 *
 * <p>인터페이스로 끊어 둔 이유는 테스트다 — 여기만 갈아 끼우면 실제 SMTP 없이 큐·재시도·백오프의
 * 동작 전부를 검증할 수 있다. 운영 구현은 {@link SmtpJavaMailSenderFactory} 하나뿐이다.
 */
public interface JavaMailSenderFactory {

    /** @param password 평문(복호화된 값). 비면 인증 없이 보낸다. */
    JavaMailSender create(MailSetting setting, String password);
}
