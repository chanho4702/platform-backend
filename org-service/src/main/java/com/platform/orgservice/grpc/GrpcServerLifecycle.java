package com.platform.orgservice.grpc;

import com.platform.common.grpc.InternalTokenServerInterceptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.services.ProtoReflectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 순정 grpc-java 서버 수명주기 — 스타터 없이 SmartLifecycle로 Boot에 편입.
 * (스펙: spring-grpc는 Boot 4 호환 확정 후 서비스 수 늘면 재평가)
 *
 * <p><b>서비스 간 인증(P-01 단계 1)</b>: 권한 서비스는 common-starter의 토큰 인터셉터를 지난다. 기본 모드는
 * monitor(토큰이 없거나 틀려도 통과 + 분당 한 줄 경고)다 — 호출자(wiki·alm·agent·migration·search)가 모두
 * 0.17.0으로 토큰을 보내기 시작한 것을 로그로 확인한 뒤 {@code PLATFORM_GRPC_AUTH_MODE=enforce}로 올린다.
 *
 * <p><b>리플렉션은 기본으로 끈다</b>({@code platform.grpc.reflection}, dev 프로필만 켬). 켜 두면 스키마 없이도
 * grpcurl로 {@code CreateGrant} 같은 쓰기 RPC를 찾아 부를 수 있다. 리플렉션도 같은 인터셉터를 지난다.
 */
@Component
@ConditionalOnProperty(value = "platform.grpc.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {

    private final PermissionGrpcService permissionGrpcService;
    private final InternalTokenServerInterceptor tokenInterceptor;
    private final int port;
    private final boolean reflection;

    private Server server;

    public GrpcServerLifecycle(PermissionGrpcService permissionGrpcService,
                               InternalTokenServerInterceptor platformGrpcServerInterceptor,
                               @Value("${platform.grpc.port:9131}") int port,
                               @Value("${platform.grpc.reflection:false}") boolean reflection) {
        this.permissionGrpcService = permissionGrpcService;
        this.tokenInterceptor = platformGrpcServerInterceptor;
        this.port = port;
        this.reflection = reflection;
    }

    /** 서버에 올릴 서비스 정의 — 전부 토큰 인터셉터를 감싼다. 테스트가 in-process 서버에 같은 구성을 올린다. */
    List<ServerServiceDefinition> services() {
        List<ServerServiceDefinition> services = new ArrayList<>();
        services.add(ServerInterceptors.intercept(permissionGrpcService, tokenInterceptor));
        if (reflection) {
            services.add(ServerInterceptors.intercept(ProtoReflectionService.newInstance(), tokenInterceptor));
        }
        return services;
    }

    @Override
    public void start() {
        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(port);
            services().forEach(builder::addService);
            server = builder.build().start();
            log.info("gRPC 서버 기동: :{} (토큰 검사 {}, 리플렉션 {})", port, tokenInterceptor.mode(),
                    reflection ? "켬" : "끔");
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
