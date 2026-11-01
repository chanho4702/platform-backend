package com.platform.orgservice.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {

    @Test
    void 수식으로_시작하는_셀은_작은따옴표로_무력화한다() {
        assertThat(Csv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(Csv.cell("+1")).isEqualTo("\"'+1\"");
        assertThat(Csv.cell("-2")).isEqualTo("\"'-2\"");
        assertThat(Csv.cell("@SUM(A1)")).isEqualTo("\"'@SUM(A1)\"");
        assertThat(Csv.cell("\tx")).isEqualTo("\"'\tx\"");
        // 가운데 들어간 기호는 수식이 아니다
        assertThat(Csv.cell("a=b")).isEqualTo("\"a=b\"");
    }

    @Test
    void 쉼표_줄바꿈_따옴표가_든_값도_한_셀로_남는다() {
        assertThat(Csv.cell("김, \"찬호\"\n")).isEqualTo("\"김, \"\"찬호\"\"\n\"");
        assertThat(Csv.cell(null)).isEqualTo("\"\"");
    }

    @Test
    void UTF8_BOM으로_시작하고_줄끝은_CRLF다() {
        byte[] out = Csv.write(List.of("이름", "팀"), List.of(Arrays.asList("홍길동", null)));
        assertThat(out[0]).isEqualTo((byte) 0xEF);
        assertThat(out[1]).isEqualTo((byte) 0xBB);
        assertThat(out[2]).isEqualTo((byte) 0xBF);
        String text = new String(out, 3, out.length - 3, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("\"이름\",\"팀\"\r\n\"홍길동\",\"\"\r\n");
    }
}
