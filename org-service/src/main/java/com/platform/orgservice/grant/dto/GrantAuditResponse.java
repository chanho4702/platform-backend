package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantAudit;

/**
 * 권한 변경 기록 한 줄.
 *
 * 필드 이름을 wiki-backend의 감사 항목과 맞춰 뒀다 — 화면이 두 기록을 한 목록으로 합치기
 * 때문이다. 다른 모양으로 주면 합치는 쪽에 변환 코드가 생기고, 그 변환이 곧 어긋난다.
 */
public record GrantAuditResponse(String id, String action, String targetType, String targetId,
                                 String targetLabel, String detail, String actorId,
                                 String createdAt) {
    public static GrantAuditResponse from(GrantAudit a) {
        return new GrantAuditResponse(
                String.valueOf(a.getId()),
                "GRANT_" + a.getAction().name(),
                a.getSubjectType().name(),
                String.valueOf(a.getSubjectId()),
                a.getSubjectLabel(),
                a.getRole().name(),
                String.valueOf(a.getActorId()),
                a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
    }
}
