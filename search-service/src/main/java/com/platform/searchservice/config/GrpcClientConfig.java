package com.platform.searchservice.config;

import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
import com.platform.common.grpc.InternalTokenClientInterceptor;
import com.platform.searchservice.content.GrpcWikiContentClient;
import com.platform.searchservice.content.WikiContentClient;
import com.platform.searchservice.permission.GrpcPermissionClient;
import com.platform.searchservice.permission.PermissionClient;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 내부 gRPC 클라이언트 둘.
 *
 * 두 채널 모두 서비스 간 토큰(PLATFORM_GRPC_TOKEN)을 메타데이터에 싣는다(P-01 단계 1·2 — common-starter
 * 클라이언트 인터셉터). 서버 쪽 검사는 org-service가 monitor로 시작해 전 서비스 송신 확인 후 enforce로 올린다.
 * 포트는 여전히 내부망 전용이다(compose에서 두 gRPC 포트 모두 호스트에 열지 않는다).
 *
 * 데드라인(P-04)은 클라이언트가 호출마다 건다 — 단건은 deadline-seconds, 백필 스트림은 stream-deadline-seconds.
 *
 * 테스트는 in-process 서버로 대체하므로 `@ConditionalOnMissingBean`을 건다.
 */
@Configuration
public class GrpcClientConfig {

    @Bean(destroyMethod = "shutdown")
    @Qualifier("orgChannel")
    ManagedChannel orgChannel(
            @Value("${platform.org-grpc.host}") String host,
            @Value("${platform.org-grpc.port}") int port,
            InternalTokenClientInterceptor platformGrpcClientInterceptor) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext()
                .intercept(platformGrpcClientInterceptor).build();
    }

    @Bean(destroyMethod = "shutdown")
    @Qualifier("wikiChannel")
    ManagedChannel wikiChannel(
            @Value("${platform.wiki-grpc.host}") String host,
            @Value("${platform.wiki-grpc.port}") int port,
            InternalTokenClientInterceptor platformGrpcClientInterceptor) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext()
                .intercept(platformGrpcClientInterceptor).build();
    }

    @Bean
    @ConditionalOnMissingBean(PermissionClient.class)
    PermissionClient permissionClient(@Qualifier("orgChannel") ManagedChannel channel,
                                      @Value("${platform.org-grpc.deadline-seconds:5}") long deadlineSeconds) {
        return new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel), deadlineSeconds);
    }

    @Bean
    @ConditionalOnMissingBean(WikiContentClient.class)
    WikiContentClient wikiContentClient(@Qualifier("wikiChannel") ManagedChannel channel,
                                        @Value("${platform.wiki-grpc.deadline-seconds:5}") long deadlineSeconds,
                                        @Value("${platform.wiki-grpc.stream-deadline-seconds:600}") long streamDeadlineSeconds) {
        return new GrpcWikiContentClient(WikiContentServiceGrpc.newBlockingStub(channel),
                deadlineSeconds, streamDeadlineSeconds);
    }
}
