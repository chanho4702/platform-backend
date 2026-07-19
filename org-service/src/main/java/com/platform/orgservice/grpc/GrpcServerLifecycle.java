package com.platform.orgservice.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 순정 grpc-java 서버 수명주기 — 스타터 없이 SmartLifecycle로 Boot에 편입.
 * (스펙: spring-grpc는 Boot 4 호환 확정 후 서비스 수 늘면 재평가)
 * 리플렉션 서비스 포함 — grpcurl로 계약 탐색/디버깅 가능.
 */
@Component
@ConditionalOnProperty(value = "platform.grpc.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {

    private final PermissionGrpcService permissionGrpcService;

    @Value("${platform.grpc.port:9131}")
    private int port;

    private Server server;

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(port)
                    .addService(permissionGrpcService)
                    .addService(ProtoReflectionService.newInstance())
                    .build()
                    .start();
            log.info("gRPC 서버 기동: :{}", port);
        } catch (IOException e) {
            throw new IllegalStateException("gRPC 서버 기동 실패 :" + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            log.info("gRPC 서버 종료: :{}", port);
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
