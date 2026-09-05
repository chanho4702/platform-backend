package com.platform.orgservice.invitation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

/**
 * 초대 메일 발송(선택).
 *
 * <p>SMTP가 설정돼 있지 않으면 아무것도 보내지 않고 {@code false}를 돌려준다 — 그러면 화면이
 * "링크를 복사해 전달하세요"로 안내한다. 메일을 못 보낸다고 초대 생성을 실패시키지 않는다:
 * 초대의 본질은 원장에 남는 행이고, 메일은 전달 수단일 뿐이다.
 *
 * <p>{@code JavaMailSender}를 Boot 자동설정(spring.mail.*)에 맡기지 않고 직접 만드는 이유는,
 * 미설정 상태를 "빈이 없음"이 아니라 "보내지 않음"으로 다루기 위해서다.
 */
@Component
@Slf4j
public class InvitationMailer {

    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm").withZone(ZoneId.systemDefault());

    private final JavaMailSender sender; // 미설정이면 null
    private final String from;

    public InvitationMailer(@Value("${platform.org.mail.host:}") String host,
                            @Value("${platform.org.mail.port:587}") int port,
                            @Value("${platform.org.mail.username:}") String username,
                            @Value("${platform.org.mail.password:}") String password,
                            @Value("${platform.org.mail.from:}") String from) {
        this.from = (from == null || from.isBlank()) ? username : from;
        if (host == null || host.isBlank()) {
            this.sender = null;
            log.info("초대 메일 미설정(platform.org.mail.host 없음) — 초대는 링크 복사로 전달한다");
            return;
        }
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host.trim());
        impl.setPort(port);
        if (username != null && !username.isBlank()) impl.setUsername(username.trim());
        if (password != null && !password.isBlank()) impl.setPassword(password);
        impl.setDefaultEncoding("UTF-8");
        Properties props = impl.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(username != null && !username.isBlank()));
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");
        this.sender = impl;
    }

    public boolean isConfigured() { return sender != null && from != null && !from.isBlank(); }

    /** @return 실제로 보냈는가. 미설정·실패 모두 false — 화면은 링크 복사로 넘어간다. */
    public boolean send(String toEmail, String inviterName, String message,
                        List<String> teamNames, String inviteUrl, Instant expiresAt) {
        if (!isConfigured()) return false;
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(toEmail);
            mail.setSubject("[플랫폼] " + inviterName + "님이 초대했습니다");
            mail.setText(body(inviterName, message, teamNames, inviteUrl, expiresAt));
            sender.send(mail);
            return true;
        } catch (Exception e) {
            // 링크는 응답으로 돌아가므로 초대 자체는 살아 있다. 주소를 로그에 남기지 않는다.
            log.warn("초대 메일 발송 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static String body(String inviterName, String message, List<String> teamNames,
                               String inviteUrl, Instant expiresAt) {
        StringBuilder sb = new StringBuilder();
        sb.append(inviterName).append("님이 플랫폼에 초대했습니다.\n\n");
        if (message != null && !message.isBlank()) {
            sb.append("남긴 말\n").append(message).append("\n\n");
        }
        if (teamNames != null && !teamNames.isEmpty()) {
            sb.append("소속될 팀: ").append(String.join(", ", teamNames)).append("\n\n");
        }
        sb.append("아래 링크로 참여하세요.\n").append(inviteUrl).append("\n\n");
        sb.append("이 링크는 ").append(EXPIRY_FORMAT.format(expiresAt)).append("까지 유효합니다.\n");
        return sb.toString();
    }
}
