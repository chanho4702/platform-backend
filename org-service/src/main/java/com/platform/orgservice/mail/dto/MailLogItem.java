package com.platform.orgservice.mail.dto;

import com.platform.orgservice.domain.MailOutbox;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 발송 로그 한 줄. 본문은 담지 않는다 — 목록에 메일 내용을 실으면 로그 화면이 곧 사서함이 된다. */
@Schema(description = "발송 로그 한 줄. 본문은 담지 않는다.")
public record MailLogItem(
        @Schema(description = "로그 id", example = "42") Long id,
        @Schema(description = "받는 주소", example = "chanho@example.com") String to,
        @Schema(description = "제목", example = "[플랫폼] 김찬호님이 초대했습니다") String subject,
        @Schema(description = "출처 — wiki | alm | org | test", example = "org") String source,
        @Schema(description = "상태", example = "SENT", allowableValues = {"PENDING", "SENT", "FAILED"}) String status,
        @Schema(description = "시도 횟수. 5회를 다 쓰면 FAILED다.", example = "1") int attempts,
        @Schema(description = "마지막 실패 사유(SMTP 원문). 성공이면 null.") String lastError,
        @Schema(description = "큐에 들어온 시각") Instant createdAt,
        @Schema(description = "보낸 시각. 아직이면 null.") Instant sentAt) {

    public static MailLogItem of(MailOutbox m) {
        return new MailLogItem(m.getId(), m.getToAddress(), m.getSubject(), m.getSource(),
                m.getStatus().name(), m.getAttempts(), m.getLastError(), m.getCreatedAt(), m.getSentAt());
    }
}
