package com.platform.orgservice.common;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 관리 화면 내려받기용 CSV(RFC 4180) 작성.
 *
 * <p>두 가지를 지킨다. 하나, <b>수식 주입(CSV injection) 차단</b> — 엑셀·구글 시트는 {@code = + - @}
 * (그리고 탭·CR)로 시작하는 셀을 수식으로 실행한다. 표시 이름은 Keycloak 프로필에서 오고 사용자가 고칠 수 있으니
 * 그런 셀 앞에 작은따옴표를 붙여 글자로 묶는다(OWASP 권고). 둘, <b>UTF-8 BOM</b> — 엑셀은 BOM이 없으면
 * CSV를 시스템 코드 페이지(CP949)로 읽어 한글이 깨진다.
 *
 * <p>모든 셀을 큰따옴표로 감싼다 — 쉼표·줄바꿈·따옴표가 든 값을 하나하나 판정하지 않아도 되고 결과가 늘 같다.
 */
public final class Csv {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private Csv() {}

    /** 헤더와 행을 CSV 바이트(UTF-8 BOM + CRLF 줄끝)로 만든다. null 셀은 빈 칸이다. */
    public static byte[] write(List<String> header, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        appendRow(out, header);
        for (List<String> row : rows) appendRow(out, row);
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    private static void appendRow(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    /** 셀 하나 — 수식 무력화 후 따옴표로 감싸고 안의 따옴표는 두 번 쓴다. */
    static String cell(String raw) {
        if (raw == null || raw.isEmpty()) return "\"\"";
        String value = isFormulaLead(raw.charAt(0)) ? "'" + raw : raw;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static boolean isFormulaLead(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }
}
