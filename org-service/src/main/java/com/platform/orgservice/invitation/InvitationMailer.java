package com.platform.orgservice.invitation;

import com.platform.orgservice.mail.MailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 초대 메일 — 본문을 만들어 플랫폼 메일 큐에 넣는다.
 *
 * <p>예전에는 여기서 {@code JavaMailSender}로 <b>초대 트랜잭션 안에서 동기 발송</b>했다. 그러면 초대
 * 생성이 SMTP 지연에 묶이고, 설정·자격증명·재시도가 위키·ALM과 따로 놀았다. 지금은 {@code mail_outbox}에
 * 행을 넣고(같은 트랜잭션 — 초대가 롤백되면 메일도 사라진다) 워커가 보낸다.
 *
 * <p>{@code mailSent}의 뜻은 그래서 <b>"큐에 넣었다"</b>이다. 배달 성공이 아니다 — 실제 결과는
 * 관리 화면의 발송 로그에 남는다. 메일을 못 보낸다고 초대 생성을 실패시키지 않는 원칙은 그대로다:
 * 초대의 본질은 원장에 남는 행이고, 메일은 전달 수단일 뿐이다(false면 화면이 링크 복사를 안내한다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvitationMailer {

    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm").withZone(ZoneId.systemDefault());

    private final MailOutboxService outbox;

    /** @return 큐에 넣었는가. 메일이 꺼져 있거나 실패면 false — 화면은 링크 복사로 넘어간다. */
    public boolean send(String toEmail, String inviterName, String message,
                        List<String> teamNames, String inviteUrl, Instant expiresAt) {
        try {
            return outbox.enqueue(List.of(toEmail), "[플랫폼] " + inviterName + "님이 초대했습니다",
                    body(inviterName, message, teamNames, inviteUrl, expiresAt), null, "org")
                    .accepted() > 0;
        } catch (Exception e) {
            // 링크는 응답으로 돌아가므로 초대 자체는 살아 있다. 주소를 로그에 남기지 않는다.
            log.warn("초대 메일 큐잉 실패: {}", e.getClass().getSimpleName());
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
