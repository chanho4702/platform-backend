package com.platform.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 공용 오류 핸들러 등록 — `com.platform.common`은 서비스의 컴포넌트 스캔 밖이라 자동 구성으로 들여온다.
 *
 * <p>Boot의 오류 MVC 자동 구성보다 앞에 선다: 그쪽 {@code basicErrorController}는 {@code ErrorController} 빈이
 * 없을 때만 생기므로, 여기서 먼저 {@link PlatformErrorController}를 두면 /error 본문도 플랫폼 계약이 된다.
 * 끄려면 {@code platform.web.error-controller.enabled=false}.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestControllerAdvice.class)
@Import(PlatformApiExceptionHandler.class)
public class PlatformWebAutoConfiguration {

    /** 중첩 구성으로 둔다 — ErrorController 타입이 없는 classpath에서 바깥 구성의 메서드 시그니처가 로딩을 깨지 않게. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.webmvc.error.ErrorController")
    @ConditionalOnProperty(prefix = "platform.web.error-controller", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class ErrorControllerConfiguration {
        @Bean
        @ConditionalOnMissingBean(type = "org.springframework.boot.webmvc.error.ErrorController")
        PlatformErrorController platformErrorController() {
            return new PlatformErrorController();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.dao.OptimisticLockingFailureException")
    @Import(PlatformOptionalExceptionHandlers.OptimisticLock.class)
    static class OptimisticLockHandlerConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
    @Import(PlatformOptionalExceptionHandlers.AccessDenied.class)
    static class AccessDeniedHandlerConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.validation.ConstraintViolationException")
    @Import(PlatformOptionalExceptionHandlers.ConstraintViolations.class)
    static class ConstraintViolationHandlerConfiguration {}
}
