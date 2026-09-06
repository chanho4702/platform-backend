package com.platform.orgservice.mail;

import com.platform.orgservice.common.PageResponse;
import com.platform.orgservice.config.ConflictResponse;
import com.platform.orgservice.mail.dto.MailLogItem;
import com.platform.orgservice.mail.dto.MailSettingResponse;
import com.platform.orgservice.mail.dto.MailSettingUpdateRequest;
import com.platform.orgservice.mail.dto.MailTestRequest;
import com.platform.orgservice.mail.dto.MailTestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 메일 설정·발송 로그(전역 관리자 전용). 인가는 {@link com.platform.orgservice.permission.PermissionFacade}가
 * 서비스 안에서 판정한다 — 이 컨트롤러는 JWT에서 사람만 꺼낸다.
 */
@Tag(name = "Mail", description = "플랫폼 메일 — 설정 한 벌과 발송 큐. 위키·ALM·초대가 모두 이 설정으로 나간다.")
@RestController
@RequestMapping("/api/org/settings/mail")
@RequiredArgsConstructor
public class MailSettingController {

    private final MailSettingService settings;
    private final MailOutboxService outbox;

    @Operation(summary = "메일 설정 조회 — 비밀번호는 담기지 않고 저장 여부만 나온다")
    @GetMapping
    public MailSettingResponse get(@AuthenticationPrincipal Jwt jwt) {
        return settings.view(userId(jwt));
    }

    @Operation(summary = "메일 설정 저장 — password는 생략=유지, \"\"=삭제, 값=교체")
    @PutMapping
    public MailSettingResponse put(@AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody MailSettingUpdateRequest req) {
        return settings.update(userId(jwt), req);
    }

    /** 실패해도 200이다 — 화면이 SMTP 문구를 그대로 보여 줘야 관리자가 무엇이 틀렸는지 안다. */
    @Operation(summary = "테스트 발송 — 동기. 실패해도 200이고 error에 SMTP 문구가 그대로 담긴다")
    @PostMapping("/test")
    public MailTestResponse test(@AuthenticationPrincipal Jwt jwt,
                                 @Valid @RequestBody(required = false) MailTestRequest req) {
        String to = (req == null || req.to() == null || req.to().isBlank())
                ? jwt.getClaimAsString("email")   // 기본은 요청한 관리자 본인
                : req.to();
        return outbox.sendTest(userId(jwt), to);
    }

    @Operation(summary = "발송 로그 조회 — 큐이자 로그다(30일 보관)")
    @GetMapping("/log")
    public PageResponse<MailLogItem> log(@AuthenticationPrincipal Jwt jwt,
                                         @Parameter(description = "상태 필터 — PENDING | SENT | FAILED. 미지정이면 전부.")
                                         @RequestParam(required = false) String status,
                                         @Parameter(description = "0부터 세는 페이지 번호")
                                         @RequestParam(defaultValue = "0") int page,
                                         @Parameter(description = "페이지 크기(최대 100)")
                                         @RequestParam(defaultValue = "20") int size) {
        return outbox.log(userId(jwt), status, page, size);
    }

    @Operation(summary = "실패한 발송 다시 보내기 — FAILED를 PENDING으로 되돌린다")
    @ConflictResponse("실패한 발송만 다시 보낼 수 있습니다.")
    @PostMapping("/log/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@AuthenticationPrincipal Jwt jwt,
                      @Parameter(description = "발송 로그 id") @PathVariable Long id) {
        outbox.retry(userId(jwt), id);
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
