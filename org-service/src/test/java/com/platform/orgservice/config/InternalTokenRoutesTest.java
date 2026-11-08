package com.platform.orgservice.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** N5 — 새 값이 "실질적으로 비었는가" 판정은 auth-server와 같다: 콤마 split → trim → 빈 항목 제거 결과가 비면 옛 값. */
class InternalTokenRoutesTest {

    @Test
    void 콤마와_공백뿐인_새_값은_비었다고_보고_옛_값으로_폴백한다() {
        assertThat(InternalTokenRoutes.orLegacy(" , ", "legacy")).isEqualTo("legacy");
        assertThat(InternalTokenRoutes.orLegacy(",,", "legacy")).isEqualTo("legacy");
        assertThat(InternalTokenRoutes.orLegacy("", "legacy")).isEqualTo("legacy");
        assertThat(InternalTokenRoutes.orLegacy(null, "legacy")).isEqualTo("legacy");
        assertThat(InternalTokenRoutes.orLegacy(" new , old ", "legacy")).isEqualTo("new , old");
        assertThat(InternalTokenRoutes.routes(" , ", null, null, "legacy").get("/internal/org/members/ensure"))
                .isEqualTo("legacy");
    }
}
