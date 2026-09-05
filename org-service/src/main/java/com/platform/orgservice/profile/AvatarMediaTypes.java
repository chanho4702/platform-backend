package com.platform.orgservice.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 클라이언트가 보낸 Content-Type은 믿지 않고 매직 바이트로 판별한다(alm·wiki 첨부와 같은 정책).
 * 아바타는 화면에 인라인으로 뜨므로 스크립트를 실을 수 없는 래스터 이미지만 통과시킨다 —
 * SVG/HTML이 프로필 사진 이름으로 들어오면 실행 벡터가 된다.
 */
public final class AvatarMediaTypes {

    public static final String OCTET_STREAM = "application/octet-stream";

    private AvatarMediaTypes() {}

    /** 판별 실패는 예외가 아니라 {@link #OCTET_STREAM} — 허용 목록에 없으므로 호출자가 400으로 거른다 */
    public static String detect(InputStream input) throws IOException {
        byte[] header = input.readNBytes(16);
        if (startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return "image/png";
        if (startsWith(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
        if (header.length >= 12
                && Arrays.equals(Arrays.copyOfRange(header, 0, 4), ascii("RIFF"))
                && Arrays.equals(Arrays.copyOfRange(header, 8, 12), ascii("WEBP"))) return "image/webp";
        return OCTET_STREAM;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
