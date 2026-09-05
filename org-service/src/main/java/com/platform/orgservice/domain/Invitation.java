package com.platform.orgservice.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Locale;

/**
 * 초대 한 건.
 *
 * <p>로그인 전에는 그 사람의 id가 없다 — 그래서 키는 이메일이고, 대조는 정규화된 {@code emailNorm}으로 한다.
 * 토큰 원문은 저장하지 않는다(sha256만): DB가 새더라도 남의 초대 링크를 만들어 낼 수 없어야 한다.
 * 그 대가로 목록 화면에 링크를 다시 보여줄 수 없고, 다시 필요하면 재발송(새 토큰)으로 얻는다.
 */
@Entity
@Table(name = "invitation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "email_norm", nullable = false, length = 320)
    private String emailNorm;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvitationStatus status;

    @Column(name = "invited_by", nullable = false)
    private Long invitedBy;

    @Column(length = 500)
    private String message;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_member_id")
    private Long acceptedMemberId;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "accepted_via", length = 16)
    private AcceptedVia acceptedVia;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Invitation of(String email, String tokenHash, long invitedBy, String message, Instant expiresAt) {
        Invitation i = new Invitation();
        i.email = email.trim();
        i.emailNorm = normalize(email);
        i.tokenHash = tokenHash;
        i.status = InvitationStatus.PENDING;
        i.invitedBy = invitedBy;
        i.message = message;
        i.expiresAt = expiresAt;
        return i;
    }

    /** 대조 키 — trim + 소문자. 초대 매칭 전체가 이메일 하나에 걸려 있으므로 규칙을 한 곳에 둔다. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isPending() { return status == InvitationStatus.PENDING; }

    public boolean isExpiredAt(Instant now) { return expiresAt != null && !expiresAt.isAfter(now); }

    public void accept(long memberId, AcceptedVia via, Instant at) {
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedMemberId = memberId;
        this.acceptedVia = via;
        this.acceptedAt = at;
    }

    public void expire() { this.status = InvitationStatus.EXPIRED; }

    public void revoke() { this.status = InvitationStatus.REVOKED; }

    /** 재발송 — 이전 토큰은 이 순간 무효가 된다(해시를 덮어쓴다). */
    public void reissue(String tokenHash, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.status = InvitationStatus.PENDING;
    }
}
