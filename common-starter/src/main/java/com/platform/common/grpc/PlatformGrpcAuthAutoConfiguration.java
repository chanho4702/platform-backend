package com.platform.common.grpc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 서비스 간 gRPC 인증(P-01 단계 1) — 인터셉터 두 개를 빈으로 준다. 서비스가 직접 건다:
 * <ul>
 *   <li>클라이언트: 채널 빌더에 {@code .intercept(platformGrpcClientInterceptor)}</li>
 *   <li>서버: {@code ServerInterceptors.intercept(service, platformGrpcServerInterceptor)}</li>
 * </ul>
 *
 * <p>설정(둘 다 env로 바로 들어온다 — 서비스 yml에 키를 따로 두지 않아도 된다):
 * <ul>
 *   <li>{@code platform.grpc.auth.token} ← {@code PLATFORM_GRPC_TOKEN} — 보낼 토큰이자 검사할 토큰(플랫폼 공용 1개)</li>
 *   <li>{@code platform.grpc.auth.mode} ← {@code PLATFORM_GRPC_AUTH_MODE} — off | monitor(기본) | enforce. 서버에만 의미가 있다.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.grpc.ServerInterceptor")
public class PlatformGrpcAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    InternalTokenClientInterceptor platformGrpcClientInterceptor(
            @Value("${platform.grpc.auth.token:${PLATFORM_GRPC_TOKEN:}}") String token) {
        return new InternalTokenClientInterceptor(token);
    }

    @Bean
    @ConditionalOnMissingBean
    InternalTokenServerInterceptor platformGrpcServerInterceptor(
            @Value("${platform.grpc.auth.mode:${PLATFORM_GRPC_AUTH_MODE:monitor}}") String mode,
            @Value("${platform.grpc.auth.token:${PLATFORM_GRPC_TOKEN:}}") String token) {
        return new InternalTokenServerInterceptor(GrpcAuthMode.parse(mode), token);
    }
}
