package com.platform.orgservice.mail;

import com.platform.orgservice.domain.MailSetting;
import com.platform.orgservice.domain.MailTls;
import com.platform.orgservice.mail.dto.MailSettingResponse;
import com.platform.orgservice.mail.dto.MailSettingUpdateRequest;
import com.platform.orgservice.permission.PermissionFacade;
import com.platform.orgservice.repository.MailSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 메일 설정의 단일 진입점 — 시드·조회·저장·비밀번호 암복호화.
 *
 * <p>운영 중 정본은 DB 한 행이고, {@code MAIL_SEED_*}는 <b>행이 없을 때 최초 1회</b>만 쓰인다.
 * 기동마다 env로 덮으면 관리 화면에서 고친 값이 재배포에 조용히 되돌아간다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailSettingService {

    private final MailSettingRepository settings;
    private final MailCrypto crypto;
    private final PermissionFacade permissions;

    /** 설치 시 고른 인프라 모드. 화면에 배지로 보여 주는 안내값이고 동작을 바꾸지는 않는다. */
    @Value("${platform.org.mail.mode:none}")
    private String mode;

    @Value("${platform.org.mail.seed.enabled:false}")
    private boolean seedEnabled;
    @Value("${platform.org.mail.seed.host:}")
    private String seedHost;
    @Value("${platform.org.mail.seed.port:587}")
    private int seedPort;
    @Value("${platform.org.mail.seed.username:}")
    private String seedUsername;
    @Value("${platform.org.mail.seed.password:}")
    private String seedPassword;
    @Value("${platform.org.mail.seed.tls:STARTTLS}")
    private String seedTls;
    @Value("${platform.org.mail.seed.from-address:}")
    private String seedFromAddress;
    @Value("${platform.org.mail.seed.from-name:}")
    private String seedFromName;

    public String mode() { return (mode == null || mode.isBlank()) ? "none" : mode.trim(); }

    // ---------------------------------------------------------------- 시드·조회

    /**
     * 행이 없으면 {@code MAIL_SEED_*}로 만든다. 이미 있으면 손대지 않는다.
     *
     * <p>시드 비밀번호는 암호화 키가 있을 때만 심는다 — 키 없이 평문으로 넣어 두면 "설정은 됐는데
     * 사실 평문"인 상태가 조용히 운영으로 나간다. 그래도 기동은 계속한다(호스트·발신자는 유효하다).
     */
    @Transactional
    public MailSetting ensureSeeded() {
        return settings.findById(MailSetting.SINGLETON_ID).orElseGet(() -> {
            String passwordEnc = null;
            if (seedPassword != null && !seedPassword.isBlank()) {
                if (crypto.isConfigured()) {
                    passwordEnc = crypto.encrypt(seedPassword);
                } else {
                    log.warn("MAIL_SEED_PASSWORD가 있지만 ORG_SETTINGS_ENC_KEY가 없어 비밀번호는 시드하지 않는다");
                }
            }
            MailSetting seeded = settings.save(MailSetting.seed(seedEnabled, seedHost, seedPort,
                    seedUsername, passwordEnc, parseTls(seedTls), seedFromAddress, seedFromName));
            log.info("메일 설정 시드: mode={} enabled={} host={}", mode(), seeded.isEnabled(),
                    seeded.getHost() == null ? "(없음)" : seeded.getHost());
            return seeded;
        });
    }

    /**
     * 지금 설정. 행이 없으면(시드 전·테스트) 꺼진 기본값을 만들어 준다 —
     * 이 서비스의 다른 경로가 "설정이 없다"는 세 번째 상태를 다루지 않게 한다.
     */
    @Transactional
    public MailSetting current() {
        return ensureSeeded();
    }

    /** 실제로 보낼 수 있는가. 소비자의 {@code /internal/org/mail/status}와 큐잉 판정이 같은 값을 본다. */
    @Transactional
    public boolean sendable() {
        return current().sendable();
    }

    /** @throws IllegalStateException 키가 없거나 바뀌었을 때. 발송 경로가 그 문구를 로그에 남긴다. */
    public String decryptPassword(MailSetting setting) {
        if (!setting.hasPassword()) return null;
        return crypto.decrypt(setting.getPasswordEnc());
    }

    // ---------------------------------------------------------------- 관리 API

    @Transactional
    public MailSettingResponse view(long actorId) {
        permissions.requireGlobalAdmin(actorId);
        return MailSettingResponse.of(current(), mode());
    }

    @Transactional
    public MailSettingResponse update(long actorId, MailSettingUpdateRequest req) {
        permissions.requireGlobalAdmin(actorId);
        MailTls tls = parseTls(req.tls());
        validate(req);

        MailSetting setting = current();
        setting.update(req.enabled(), req.host(), req.port(), req.username(), tls,
                req.fromAddress(), req.fromName(), actorId);

        // 생략(null)이면 유지, 빈 문자열이면 삭제, 값이 있으면 교체.
        if (req.password() != null) {
            if (req.password().isBlank()) {
                setting.changePassword(null);
            } else {
                if (!crypto.isConfigured()) {
                    throw new IllegalArgumentException("메일 비밀번호를 저장하려면 ORG_SETTINGS_ENC_KEY가 필요합니다");
                }
                setting.changePassword(crypto.encrypt(req.password()));
            }
        }
        settings.saveAndFlush(setting);
        log.info("메일 설정 저장: actor={} enabled={} host={}", actorId, setting.isEnabled(), setting.getHost());
        return MailSettingResponse.of(setting, mode());
    }

    /**
     * 켜 두고 호스트나 발신자를 비우면 SMTP 단계에서야 실패해 로그에 원인이 흐릿하게 남는다 —
     * 저장 시점에 막는다. 꺼 두는 것은 언제나 허용한다(끄려면 값을 지워야 한다면 끄기가 번거롭다).
     */
    private static void validate(MailSettingUpdateRequest req) {
        if (!req.enabled()) return;
        if (isBlank(req.host())) throw new IllegalArgumentException("메일을 사용하려면 호스트가 필요합니다");
        if (req.port() < 1 || req.port() > 65535) {
            throw new IllegalArgumentException("포트는 1~65535 사이여야 합니다");
        }
        if (isBlank(req.fromAddress())) throw new IllegalArgumentException("메일을 사용하려면 보내는 주소가 필요합니다");
        if (!req.fromAddress().contains("@")) {
            throw new IllegalArgumentException("보내는 주소가 이메일 형식이 아닙니다");
        }
    }

    static MailTls parseTls(String raw) {
        if (raw == null || raw.isBlank()) return MailTls.STARTTLS;
        try {
            return MailTls.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("전송 보안은 NONE·STARTTLS·SSL 중 하나여야 합니다");
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
