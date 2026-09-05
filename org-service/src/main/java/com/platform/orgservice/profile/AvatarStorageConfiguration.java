package com.platform.orgservice.profile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.file.Path;

/**
 * 저장소 선택. {@code platform.org.avatar.s3.enabled=true}면 S3 호환(MinIO), 아니면 로컬 파일.
 * 기본이 로컬인 이유는 dev 오프셋 클러스터가 MinIO 없이 뜨기 때문이다 — 운영 compose가 켠다.
 */
@Configuration(proxyBeanMethods = false)
public class AvatarStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "platform.org.avatar.s3", name = "enabled", havingValue = "true")
    static class S3 {
        @Bean(destroyMethod = "close")
        S3Client orgAvatarS3Client(
                @Value("${platform.org.avatar.s3.region}") String region,
                @Value("${platform.org.avatar.s3.endpoint:}") String endpoint,
                @Value("${platform.org.avatar.s3.path-style-access:true}") boolean pathStyleAccess,
                @Value("${platform.org.avatar.s3.access-key:}") String accessKey,
                @Value("${platform.org.avatar.s3.secret-key:}") String secretKey) {
            S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(region))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(pathStyleAccess)
                            .build());
            if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
            if (!accessKey.isBlank() || !secretKey.isBlank()) {
                if (accessKey.isBlank() || secretKey.isBlank()) {
                    throw new IllegalArgumentException("S3 access-key와 secret-key는 함께 설정해야 합니다");
                }
                builder.credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
            }
            return builder.build();
        }

        @Bean
        AvatarStorage s3AvatarStorage(
                S3Client orgAvatarS3Client,
                @Value("${platform.org.avatar.s3.bucket}") String bucket) {
            return new S3AvatarStorage(orgAvatarS3Client, bucket);
        }
    }

    @Bean
    @ConditionalOnMissingBean(AvatarStorage.class)
    AvatarStorage localAvatarStorage(@Value("${platform.org.avatar.files-dir}") String filesDir) {
        return new LocalAvatarStorage(Path.of(filesDir));
    }
}
