package com.platform.orgservice.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할 계층(W23). COMMENTER가 보기와 편집 사이에 끼면서, 기존 VIEWER는 더는 댓글을 못 단다 —
 * 그것이 이 역할을 두는 이유다(공지·규정 스페이스).
 */
class GrantRoleTest {

    @Test
    void 계층은_ADMIN_EDITOR_COMMENTER_VIEWER_순이다() {
        assertThat(GrantRole.VIEWER.covers(PermAction.VIEW)).isTrue();
        assertThat(GrantRole.VIEWER.covers(PermAction.COMMENT)).isFalse();
        assertThat(GrantRole.COMMENTER.covers(PermAction.COMMENT)).isTrue();
        assertThat(GrantRole.COMMENTER.covers(PermAction.EDIT)).isFalse();
        assertThat(GrantRole.EDITOR.covers(PermAction.COMMENT)).isTrue();
        assertThat(GrantRole.ADMIN.covers(PermAction.COMMENT)).isTrue();
    }
}
