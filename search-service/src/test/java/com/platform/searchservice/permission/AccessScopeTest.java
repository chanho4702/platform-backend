package com.platform.searchservice.permission;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccessScopeTest {

    @Test
    void 요청_spaceIds는_접근_가능_집합을_넓히지_못한다() {
        AccessScope scope = AccessScope.of(Set.of(10L, 20L));

        assertThat(scope.resolveFilter(Set.of(20L, 999L))).containsExactly(20L);
        assertThat(scope.resolveFilter(Set.of(999L))).isEmpty();
        assertThat(scope.resolveFilter(Set.of())).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void GLOBAL은_클라이언트가_요청한_범위만_필터로_돌려준다() {
        assertThat(AccessScope.global().resolveFilter(Set.of(999L))).containsExactly(999L);
        assertThat(AccessScope.global().resolveFilter(Set.of())).isEmpty();
    }
}
