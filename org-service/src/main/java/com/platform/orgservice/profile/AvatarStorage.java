package com.platform.orgservice.profile;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * 아바타 바이트 저장소. 운영은 S3 호환(MinIO), 개발·테스트는 로컬 파일 — 서비스는 구현을 모른다.
 * alm-backend의 {@code AttachmentStorage}와 같은 구조를 최소로 복제한 것이다(스펙 §1.2):
 * 공통 스타터로 끌어올리지 않은 이유는 쓰는 곳이 둘뿐이고 common-starter가 AWS SDK 의존을
 * 다섯 서비스 전부에 지우게 되기 때문이다.
 *
 * 키는 호출자가 정한다(아바타는 자체 메타 테이블 없이 키만 들고 있다). 삭제 실패는 예외가 아니라
 * false — 메타 삭제를 되돌리지 않는다.
 */
public interface AvatarStorage {

    /** @return 저장된 오브젝트의 (bucket, key). 로컬 저장소는 bucket이 null이다 */
    StoredObject store(InputStream input, long contentLength, String contentType, String key);

    /**
     * 저장소 밖을 가리키는 키를 거른다 — 구현이 파일 경로를 쓰든 오브젝트 키를 쓰든 같은 계약이다.
     *
     * S3는 {@code ..}를 경로로 해석하지 않아 탈출이 성립하지 않지만, 여기서 함께 막아야
     * 저장소를 바꿔 끼웠을 때 같은 키가 로컬 파일 저장소에서 갑자기 위험해지는 일이 없다.
     */
    static String requireSafeKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")
                || key.contains("\\") || key.contains("..")) {
            throw new IllegalArgumentException("잘못된 아바타 키입니다");
        }
        return key;
    }

    /**
     * 이 저장소가 오브젝트를 넣는 bucket. 프로필 행에 bucket을 따로 저장하지 않으므로
     * {@link #open}·{@link #delete}에 이 값을 넘긴다. 로컬 파일 저장소는 bucket 개념이 없어 null이다.
     */
    default String defaultBucket() { return null; }

    Resource open(String bucket, String key);

    boolean delete(String bucket, String key);

    record StoredObject(String bucket, String key) {}
}
