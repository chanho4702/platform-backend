package com.platform.orgservice.me.dto;

import com.platform.orgservice.domain.GrantEntry;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 권한 한 줄. 프론트가 메뉴·버튼 노출을 이 목록으로 판정한다.")
public record GrantResponse(
        @Schema(description = "리소스 종류", example = "SPACE",
                allowableValues = {"GLOBAL", "SPACE", "PROJECT"}) String resourceType,
        @Schema(description = "리소스 식별자. GLOBAL이면 null이거나 빈 문자열.", example = "sp-1") String resourceId,
        @Schema(description = "역할. ADMIN ⊃ EDITOR ⊃ COMMENTER ⊃ VIEWER.", example = "EDITOR",
                allowableValues = {"VIEWER", "COMMENTER", "EDITOR", "ADMIN"}) String role) {
    public static GrantResponse from(GrantEntry g) {
        return new GrantResponse(g.getResourceType().name(), g.getResourceId(), g.getRole().name());
    }
}
