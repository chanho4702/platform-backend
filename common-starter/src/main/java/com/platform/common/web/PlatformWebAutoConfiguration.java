package com.platform.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 공용 오류 핸들러 등록 — `com.platform.common`은 서비스의 컴포넌트 스캔 밖이라 자동 구성으로 들여온다. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestControllerAdvice.class)
@Import(PlatformApiExceptionHandler.class)
public class PlatformWebAutoConfiguration {
}
