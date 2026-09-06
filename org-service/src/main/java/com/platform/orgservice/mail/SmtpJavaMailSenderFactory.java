package com.platform.orgservice.mail;

import com.platform.orgservice.domain.MailSetting;
import com.platform.orgservice.domain.MailTls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * 실제 SMTP 발송기 조립.
 *
 * <p>Boot 자동설정({@code spring.mail.*})에 맡기지 않는 이유는 설정이 <b>런타임에 바뀌기</b> 때문이다 —
 * 관리 화면이 호스트를 고치면 재기동 없이 다음 발송부터 새 값으로 나가야 한다.
 *
 * <p>타임아웃을 반드시 건다. 안 걸면 죽은 SMTP 하나가 워커 스레드를 영구히 붙잡아 큐 전체가 멈춘다.
 */
@Component
public class SmtpJavaMailSenderFactory implements JavaMailSenderFactory {

    private final int timeoutMs;

    public SmtpJavaMailSenderFactory(@Value("${platform.org.mail.smtp-timeout-ms:10000}") int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public JavaMailSender create(MailSetting setting, String password) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(setting.getHost());
        impl.setPort(setting.getPort());
        impl.setDefaultEncoding("UTF-8");

        boolean auth = setting.getUsername() != null && !setting.getUsername().isBlank();
        if (auth) {
            impl.setUsername(setting.getUsername());
            impl.setPassword(password == null ? "" : password);
        }

        Properties props = impl.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(auth));
        MailTls tls = setting.getTls() == null ? MailTls.STARTTLS : setting.getTls();
        // STARTTLS는 "요구"까지 켠다 — enable만 켜면 서버가 안 내밀 때 조용히 평문으로 나간다.
        props.put("mail.smtp.starttls.enable", String.valueOf(tls == MailTls.STARTTLS));
        props.put("mail.smtp.starttls.required", String.valueOf(tls == MailTls.STARTTLS));
        props.put("mail.smtp.ssl.enable", String.valueOf(tls == MailTls.SSL));
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));
        return impl;
    }
}
