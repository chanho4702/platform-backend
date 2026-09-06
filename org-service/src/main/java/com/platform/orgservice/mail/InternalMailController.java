package com.platform.orgservice.mail;

import com.platform.orgservice.mail.dto.InternalMailRequest;
import com.platform.orgservice.mail.dto.InternalMailResponse;
import com.platform.orgservice.mail.dto.MailStatusResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 전용(wiki·alm → org-service). 게이트웨이는 {@code /internal/**}을 라우팅하지 않고,
 * {@link com.platform.orgservice.security.InternalTokenFilter}가 {@code X-Internal-Token}을 검사한다.
 *
 * <p>발송은 플랫폼에서 한 곳(여기)에서만 한다 — 설정·자격증명·재시도·로그가 서비스마다 갈라지면
 * "메일이 안 갔다"를 어디서 봐야 하는지부터 달라진다. <b>수신자 결정은 여전히 호출측</b>이다:
 * 누가 구독했고 누가 차단됐는지는 그 서비스만 안다.
 *
 * <p>{@code @Hidden}: 내부 전용이라 OpenAPI 스펙에 싣지 않는다.
 */
@Hidden
@RestController
@RequestMapping("/internal/org/mail")
@RequiredArgsConstructor
public class InternalMailController {

    private final MailOutboxService outbox;
    private final MailSettingService settings;

    /** 202 — 큐에 넣었다는 뜻이고 배달을 보장하지 않는다. 결과는 관리 화면의 발송 로그에 남는다. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InternalMailResponse enqueue(@Valid @RequestBody InternalMailRequest req) {
        MailOutboxService.EnqueueResult result =
                outbox.enqueue(req.to(), req.subject(), req.text(), req.html(), req.source());
        return new InternalMailResponse(result.accepted(), result.disabled());
    }

    /** 소비자가 "메일 켜짐" UI를 그릴 근거. 소비자 쪽에서 60초 캐시한다. */
    @GetMapping("/status")
    public MailStatusResponse status() {
        return new MailStatusResponse(settings.sendable());
    }
}
