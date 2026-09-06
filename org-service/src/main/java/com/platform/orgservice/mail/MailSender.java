package com.platform.orgservice.mail;

import com.platform.orgservice.domain.MailOutbox;
import com.platform.orgservice.domain.MailSetting;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 한 통을 실제로 보낸다.
 *
 * <p>{@link JavaMailSender}는 설정이 바뀔 때만 다시 조립한다. 매번 새로 만들면 세션 캐시를 버리게 되고,
 * 한 번 만들고 붙들면 관리 화면에서 고친 값이 재기동 전까지 안 먹는다. 그래서 <b>설정 내용 자체를
 * 버전 키</b>로 삼는다 — 저장이 곧 무효화라 별도 신호가 필요 없다. (비밀번호는 암호문을 키에 넣는다.
 * 키를 만들자고 매번 복호화하지 않기 위해서다.)
 *
 * <p>항상 {@code MimeMessage}로 보낸다 — {@code SimpleMailMessage}로는 보내는 <b>이름</b>을 붙일 수 없어
 * 받는 쪽에 주소만 뜬다.
 */
@Component
@RequiredArgsConstructor
public class MailSender {

    private final MailSettingService settings;
    private final JavaMailSenderFactory factory;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    private record Cached(String version, JavaMailSender sender) {}

    /**
     * @throws org.springframework.mail.MailException SMTP 실패 — 호출측(워커·테스트 발송)이
     *         메시지를 그대로 사용자에게 보여 준다.
     * @throws IllegalStateException 비밀번호를 복호화할 수 없거나(암호화 키가 바뀜) 본문을 못 만들 때
     */
    public void send(MailSetting setting, MailOutbox message) {
        JavaMailSender sender = senderFor(setting);
        MimeMessage mime = sender.createMimeMessage();
        try {
            boolean html = message.getBodyHtml() != null;
            MimeMessageHelper helper = new MimeMessageHelper(mime, html, "UTF-8");
            setFrom(helper, setting);
            helper.setTo(message.getToAddress());
            helper.setSubject(message.getSubject());
            if (html) {
                // multipart/alternative — 텍스트만 읽는 클라이언트도 내용을 본다.
                helper.setText(message.getBodyText(), message.getBodyHtml());
            } else {
                helper.setText(message.getBodyText(), false);
            }
        } catch (jakarta.mail.MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("메일 본문을 만들 수 없습니다: " + e.getMessage(), e);
        }
        sender.send(mime);
    }

    private JavaMailSender senderFor(MailSetting setting) {
        String version = version(setting);
        Cached cached = cache.get();
        if (cached != null && cached.version().equals(version)) return cached.sender();
        JavaMailSender created = factory.create(setting, settings.decryptPassword(setting));
        cache.set(new Cached(version, created));
        return created;
    }

    private static String version(MailSetting s) {
        return String.join("|",
                String.valueOf(s.getHost()), String.valueOf(s.getPort()), String.valueOf(s.getUsername()),
                String.valueOf(s.getTls()), String.valueOf(s.getPasswordEnc()));
    }

    private static void setFrom(MimeMessageHelper helper, MailSetting s)
            throws jakarta.mail.MessagingException, UnsupportedEncodingException {
        if (s.getFromName() != null && !s.getFromName().isBlank()) {
            helper.setFrom(s.getFromAddress(), s.getFromName());
        } else {
            helper.setFrom(s.getFromAddress());
        }
    }
}
