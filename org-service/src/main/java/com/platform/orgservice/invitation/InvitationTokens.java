package com.platform.orgservice.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 초대 토큰 생성·대조.
 *
 * <p>원문은 링크에만 실리고 DB에는 sha256만 남는다 — 초대 링크는 "로그인 없이 이 이메일로 들어올 수 있다"는
 * 자격이라, 원문을 저장하면 DB 유출이 곧 계정 탈취가 된다. 대가로 목록 화면에 링크를 다시 보여줄 수 없다
 * (재발송이 새 토큰을 만든다).
 */
public final class InvitationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private InvitationTokens() {}

    /** URL-safe 32바이트 난수(패딩 없음). 경로 세그먼트에 그대로 실린다. */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** sha256 소문자 hex(64자). 저장·조회 모두 이 값으로만 한다. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
