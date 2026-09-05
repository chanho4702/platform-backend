package com.platform.orgservice.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 멤버 프로필(V7) — 지금은 아바타뿐이다. member 테이블에 컬럼을 더하지 않고 별도 테이블로 둔다:
 * member는 auth-server 클레임의 JIT 미러라 로그인이 지나갈 때마다 갱신되는 행이고,
 * 사용자가 올린 자산의 수명은 그것과 다르다.
 */
@Entity
@Table(name = "member_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberProfile {

    @Id
    @Column(name = "member_id")
    private Long memberId; // = member.id = auth-server user id(JWT sub)

    /** 아바타 오브젝트 키({@code avatars/{memberId}/{uuid}.{ext}}) */
    @Column(name = "avatar_key", length = 200)
    private String avatarKey;

    /** 업로드 때 매직 바이트로 판별한 타입 — 읽을 때 이 값을 그대로 돌려준다 */
    @Column(name = "avatar_content_type", length = 80)
    private String avatarContentType;

    /** 캐시 무효화용(?v=). updatedAt과 분리해야 아바타와 무관한 프로필 갱신이 이미지 URL을 흔들지 않는다 */
    @Column(name = "avatar_updated_at")
    private Instant avatarUpdatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static MemberProfile of(long memberId, Instant at) {
        MemberProfile profile = new MemberProfile();
        profile.memberId = memberId;
        profile.updatedAt = at;
        return profile;
    }

    /** @return 방금 밀려난 이전 키(없으면 null) — 호출자가 커밋 뒤에 지운다 */
    public String attachAvatar(String key, String contentType, Instant at) {
        String previous = this.avatarKey;
        this.avatarKey = key;
        this.avatarContentType = contentType;
        this.avatarUpdatedAt = at;
        this.updatedAt = at;
        return previous;
    }

    /** @return 지워야 할 키(없었으면 null) */
    public String clearAvatar(Instant at) {
        String previous = this.avatarKey;
        this.avatarKey = null;
        this.avatarContentType = null;
        this.avatarUpdatedAt = null;
        this.updatedAt = at;
        return previous;
    }

    public boolean hasAvatar() { return avatarKey != null && !avatarKey.isBlank(); }

    /**
     * 아바타 이미지 주소(없으면 null).
     *
     * <b>{@code <img src>}에 그대로 넣을 수 없다.</b> 이 엔드포인트는 Bearer 인증을 요구하는데
     * 브라우저는 {@code <img>} 요청에 Authorization 헤더를 붙이지 않아 401이 난다. 프론트는
     * fetch로 바이트를 받아 object URL을 만들어 쓴다 — 이 문자열은 "아바타가 있다"는 신호이자
     * fetch 대상 경로다. {@code ?v=}는 캐시버스터일 뿐이고 서버는 읽지 않는다.
     */
    public String avatarUrl() {
        if (!hasAvatar()) return null;
        long version = avatarUpdatedAt == null ? 0L : avatarUpdatedAt.toEpochMilli();
        return "/api/org/members/" + memberId + "/avatar?v=" + version;
    }
}
