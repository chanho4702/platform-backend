package com.platform.searchservice.config;

import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
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
 * 채널에 인증이 없다 — wiki→org 호출과 같은 상태이고 플랫폼 전역 defer 항목이다.
 * 내부망에서만 접근 가능한 것으로 막는다(compose에서 두 gRPC 포트 모두 호스트에 열지 않는다).
 *
 * 테스트는 in-process 서버로 대체하므로 `@ConditionalOnMissingBean`을 건다.
 */
@Configuration
public class GrpcClientConfig {

    @Bean(destroyMethod = "shutdown")
    @Qualifier("orgChannel")
    ManagedChannel orgChannel(
            @Value("${platform.org-grpc.host}") String host,
            @Value("${platform.org-grpc.port}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    @Bean(destroyMethod = "shutdown")
    @Qualifier("wikiChannel")
    ManagedChannel wikiChannel(
            @Value("${platform.wiki-grpc.host}") String host,
            @Value("${platform.wiki-grpc.port}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    @Bean
    @ConditionalOnMissingBean(PermissionClient.class)
    PermissionClient permissionClient(@Qualifier("orgChannel") ManagedChannel channel) {
        return new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel));
    }

    @Bean
    @ConditionalOnMissingBean(WikiContentClient.class)
    WikiContentClient wikiContentClient(@Qualifier("wikiChannel") ManagedChannel channel) {
        return new GrpcWikiContentClient(WikiContentServiceGrpc.newBlockingStub(channel));
    }
}
