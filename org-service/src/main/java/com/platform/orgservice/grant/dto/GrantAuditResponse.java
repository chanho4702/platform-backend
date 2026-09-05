package com.platform.orgservice.grant.dto;

import com.platform.orgservice.domain.GrantAudit;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 권한 변경 기록 한 줄.
 *
 * 필드 이름을 wiki-backend의 감사 항목과 맞춰 뒀다 — 화면이 두 기록을 한 목록으로 합치기
 * 때문이다. 다른 모양으로 주면 합치는 쪽에 변환 코드가 생기고, 그 변환이 곧 어긋난다.
 */
@Schema(description = "권한 변경 기록 한 줄. 필드 이름은 wiki-backend의 감사 항목과 맞춰 뒀다 — 화면이 두 기록을 한 목록으로 합친다.")
public record GrantAuditResponse(
        @Schema(description = "기록 id. 숫자를 문자열로 낸다.", example = "108") String id,
        @Schema(description = "무슨 일이 있었나 — GRANT_GRANTED | GRANT_CHANGED | GRANT_REVOKED", example = "GRANT_GRANTED") String action,
        @Schema(description = "권한 주체 종류 — USER | TEAM", example = "USER") String targetType,
        @Schema(description = "권한 주체 id. 숫자를 문자열로 낸다.", example = "42") String targetId,
        @Schema(description = "기록 당시의 주체 이름", example = "김찬호") String targetLabel,
        @Schema(description = "역할 이름", example = "EDITOR") String detail,
        @Schema(description = "이 일을 한 사람의 멤버 id. 숫자를 문자열로 낸다.", example = "1") String actorId,
        @Schema(description = "발생 시각(ISO-8601 문자열)", example = "2026-09-05T02:11:43Z") String createdAt) {
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
