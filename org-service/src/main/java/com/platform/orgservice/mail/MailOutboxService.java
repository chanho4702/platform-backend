package com.platform.orgservice.mail;

import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.orgservice.common.PageResponse;
import com.platform.orgservice.domain.MailOutbox;
import com.platform.orgservice.domain.MailSetting;
import com.platform.orgservice.domain.MailStatus;
import com.platform.orgservice.mail.dto.MailLogItem;
import com.platform.orgservice.mail.dto.MailTestResponse;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.MailOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 발송 큐 — 넣기(소비자·초대), 빼서 보내기(워커), 보기·다시 보내기(관리 화면).
 *
 * <p>넣기는 호출측 트랜잭션에 <b>참여한다</b>. 초대가 롤백되면 그 초대 메일도 함께 사라져야 하고,
 * 초대가 커밋되면 메일은 이미 큐에 있어 SMTP가 죽어 있어도 나중에 나간다(outbox 패턴).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailOutboxService {

    private static final int MAX_LOG_PAGE_SIZE = 100;

    private final MailOutboxRepository outbox;
    private final MailSettingService settings;
    private final MailSender sender;
    private final PermissionFacade permissions;

    @Value("${platform.org.mail.outbox.batch-size:20}")
    private int batchSize;

    @Value("${platform.org.mail.outbox.retention-days:30}")
    private int retentionDays;

    /** 큐잉 결과. {@code disabled}면 아무것도 넣지 않았다 — 오류가 아니라 "메일이 꺼져 있음"이다. */
    public record EnqueueResult(int accepted, boolean disabled) {}

    // ---------------------------------------------------------------- 넣기

    /**
     * 여러 주소로 한 통씩. 빈 주소는 버리고 중복은 하나로 친다 —
     * 소비자가 구독자 목록을 합쳐 넘기면 같은 사람이 두 번 들어오는 일이 흔하다.
     */
    @Transactional
    public EnqueueResult enqueue(List<String> to, String subject, String text, String html, String source) {
        MailSetting setting = settings.current();
        if (!setting.sendable()) return new EnqueueResult(0, true);

        Instant now = Instant.now();
        String normalizedSource = normalizeSource(source);
        int accepted = 0;
        for (String address : distinct(to)) {
            outbox.save(MailOutbox.queued(address, subject, text, html, normalizedSource, now));
            accepted++;
        }
        return new EnqueueResult(accepted, false);
    }

    // ---------------------------------------------------------------- 빼서 보내기

    /**
     * 차례가 된 것들을 집어 보낸다. 한 통의 실패가 배치를 되돌리지 않도록 통마다 잡아 기록한다 —
     * 롤백되면 시도 횟수가 안 늘어 같은 메일이 영원히 다시 시도된다.
     *
     * @return 이번에 보낸 통수
     */
    @Transactional
    public int drainOnce() {
        Instant now = Instant.now();
        List<MailOutbox> due = outbox.claimDue(now, PageRequest.of(0, Math.max(batchSize, 1)));
        if (due.isEmpty()) return 0;

        MailSetting setting = settings.current();
        int sent = 0;
        for (MailOutbox message : due) {
            if (!setting.sendable()) {
                // 큐에 남긴 채로 둔다 — 다시 켜면 그대로 나간다. 여기서 FAILED로 눕히면 복구가 수동이 된다.
                message.markAttemptFailed("메일 발송이 꺼져 있습니다", now);
                continue;
            }
            try {
                sender.send(setting, message);
                message.markSent(Instant.now());
                sent++;
            } catch (Exception e) {
                // 주소는 남기지 않는다(로그가 곧 주소록이 된다). 사유는 행에 그대로 실린다.
                log.warn("메일 발송 실패: id={} source={} 사유={}", message.getId(), message.getSource(), reason(e));
                message.markAttemptFailed(reason(e), Instant.now());
            }
        }
        return sent;
    }

    /** 30일 지난 SENT/FAILED 정리. 큐이자 로그이므로 무한히 쌓이게 두지 않는다. */
    @Transactional
    public int cleanup() {
        int deleted = outbox.deleteSettledBefore(Instant.now().minus(Duration.ofDays(Math.max(retentionDays, 1))));
        if (deleted > 0) log.info("메일 로그 정리: {}건", deleted);
        return deleted;
    }

    // ---------------------------------------------------------------- 관리 API

    @Transactional
    public MailTestResponse sendTest(long actorId, String to) {
        permissions.requireGlobalAdmin(actorId);
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("받는 주소가 필요합니다");
        }
        MailSetting setting = settings.current();
        if (!setting.sendable()) {
            return MailTestResponse.failed("메일 발송이 꺼져 있거나 호스트·보내는 주소가 비어 있습니다");
        }
        MailOutbox message = outbox.save(MailOutbox.queued(to.trim(), "[플랫폼] 메일 설정 테스트",
                """
                        플랫폼 메일 설정이 올바르게 동작합니다.

                        이 메일은 관리 화면의 [테스트 발송]으로 보낸 것입니다.""",
                null, "test", Instant.now()));
        try {
            // 테스트는 동기다 — 관리자가 저장 직후 화면에서 결과를 봐야 설정을 고칠 수 있다.
            sender.send(setting, message);
            message.markSent(Instant.now());
            return MailTestResponse.sent();
        } catch (Exception e) {
            message.markAttemptFailed(reason(e), Instant.now());
            return MailTestResponse.failed(reason(e));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<MailLogItem> log(long actorId, String status, int page, int size) {
        permissions.requireGlobalAdmin(actorId);
        MailStatus filter = parseStatus(status);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<MailOutbox> found = filter == null
                ? outbox.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : outbox.findByStatusOrderByCreatedAtDescIdDesc(filter, pageable);
        return PageResponse.of(found, MailLogItem::of);
    }

    /** 실패한 것만 다시 민다 — 이미 보낸 것을 다시 보내면 같은 메일이 두 번 간다. */
    @Transactional
    public void retry(long actorId, long id) {
        permissions.requireGlobalAdmin(actorId);
        MailOutbox message = outbox.findById(id)
                .orElseThrow(() -> new NotFoundException("발송 기록을 찾을 수 없습니다"));
        if (message.getStatus() != MailStatus.FAILED) {
            throw new ConflictException("실패한 발송만 다시 보낼 수 있습니다");
        }
        message.requeue(Instant.now());
    }

    // ---------------------------------------------------------------- 도우미

    static MailStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) return null;
        try {
            return MailStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("상태는 PENDING·SENT·FAILED 중 하나여야 합니다");
        }
    }

    private static List<String> distinct(List<String> to) {
        if (to == null) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : to) {
            if (raw == null || raw.isBlank()) continue;
            String address = raw.trim();
            if (seen.add(address.toLowerCase(Locale.ROOT))) out.add(address);
        }
        return out;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) return "org";
        String normalized = source.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }

    /** SMTP가 알려 준 문구를 그대로 쓴다 — 관리자가 호스트·인증·TLS 중 무엇이 틀렸는지 아는 유일한 단서다. */
    private static String reason(Exception e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) return message;
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) return cause.getMessage();
        return e.getClass().getSimpleName();
    }

    private static int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, MAX_LOG_PAGE_SIZE);
    }
}
