package com.platform.orgservice.mail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 소비자(wiki·alm)가 넘기는 발송 요청. <b>수신자 결정은 호출측 몫</b>이다 — 누가 구독했고 누가
 * 차단됐는지는 그 서비스만 안다. 여기서는 주소를 그대로 큐에 넣는다.
 *
 * <p>상한 100은 한 번의 실수가 큐를 덮지 않게 하는 선이다. 넘으면 400이고, 호출측이 나눠 보낸다.
 */
public record InternalMailRequest(
        @NotEmpty(message = "받는 주소가 필요합니다")
        @Size(max = 100, message = "받는 주소는 100개까지입니다") List<String> to,
        @NotBlank(message = "제목이 필요합니다")
        @Size(max = 500, message = "제목은 500자 이하여야 합니다") String subject,
        @NotBlank(message = "본문이 필요합니다") String text,
        String html,
        @NotBlank(message = "출처(source)가 필요합니다")
        @Size(max = 32, message = "출처는 32자 이하여야 합니다") String source) {
}
