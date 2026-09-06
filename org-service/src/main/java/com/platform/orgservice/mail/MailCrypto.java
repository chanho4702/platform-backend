package com.platform.orgservice.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * SMTP 비밀번호 암복호화(AES-256-GCM).
 *
 * <p>키는 {@code ORG_SETTINGS_ENC_KEY}(32바이트 hex = 64자)다. <b>키가 없으면 비밀번호를 저장하지
 * 않는다</b> — 평문 폴백을 두면 "설정은 됐는데 사실 평문"인 상태가 조용히 운영으로 나간다.
 * 저장 시도는 400으로 거부하고, 키가 없어도 나머지 설정(호스트·포트·발신자)은 정상 동작한다.
 *
 * <p>저장 형식은 base64({@code iv 12바이트 || 암호문+태그 16바이트})다. IV는 매 저장마다 새로 뽑는다 —
 * GCM에서 같은 키로 IV를 재사용하면 평문이 드러난다.
 */
@Component
public class MailCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key; // 미설정이면 null
    private final SecureRandom random = new SecureRandom();

    public MailCrypto(@Value("${platform.org.mail.enc-key:}") String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = HexFormat.of().parseHex(hexKey.trim());
        } catch (IllegalArgumentException e) {
            // 기동을 세운다: 잘못된 키로 뜨면 저장된 비밀번호를 못 읽는 채로 "정상"처럼 보인다.
            throw new IllegalStateException("ORG_SETTINGS_ENC_KEY는 32바이트 hex(64자)여야 합니다");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("ORG_SETTINGS_ENC_KEY는 32바이트 hex(64자)여야 합니다");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isConfigured() { return key != null; }

    /** @throws IllegalStateException 키 미설정 — 호출측이 먼저 {@link #isConfigured()}로 걸러야 한다. */
    public String encrypt(String plain) {
        if (key == null) throw new IllegalStateException("암호화 키가 없습니다");
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("메일 비밀번호를 암호화할 수 없습니다", e);
        }
    }

    /**
     * @throws IllegalStateException 키가 없거나 바뀌었을 때. 발송 경로에서는 이 문구가 그대로
     *         {@code mail_outbox.last_error}에 남아 관리자가 원인을 본다.
     */
    public String decrypt(String stored) {
        if (key == null) throw new IllegalStateException("메일 비밀번호를 복호화할 수 없습니다 — ORG_SETTINGS_ENC_KEY가 없습니다");
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_BYTES) throw new IllegalArgumentException("암호문이 너무 짧습니다");
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("메일 비밀번호를 복호화할 수 없습니다 — ORG_SETTINGS_ENC_KEY가 바뀌었습니다");
        }
    }
}
