package com.platform.orgservice.member.dto;

import com.platform.orgservice.domain.MemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 상태 전이. 표시 이름은 여기서 바꾸지 않는다 — {@code MemberMirrorFilter}가 요청마다 JWT의
 * {@code name}으로 덮어쓰므로 고쳐 봐야 다음 로그인에 되돌아간다(원천은 Keycloak 프로필).
 */
@Schema(description = "멤버 상태 전이 요청. 표시 이름은 여기서 바꿀 수 없다(원천은 Keycloak 프로필).")
public record MemberPatchRequest(
        @Schema(description = "전이할 상태. PENDING으로 되돌리거나 자기 계정을 비활성화하면 409.",
                example = "SUSPENDED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "변경할 상태를 지정하세요") MemberStatus status) {}
