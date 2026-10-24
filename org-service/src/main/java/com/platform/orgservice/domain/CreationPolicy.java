package com.platform.orgservice.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 리소스 종류(SPACE/PROJECT)마다 한 행 — "누가 새로 만들 수 있는가"(V10).
 *
 * <p>행이 없으면 {@link CreationMode#ALL_ACTIVE}로 본다 — 시드 없이 기존 동작(활성 구성원 누구나)이 유지된다.
 */
@Entity
@Table(name = "creation_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreationPolicy {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", length = 16)
    private ResourceKind resourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CreationMode mode;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "creation_policy_team", joinColumns = @JoinColumn(name = "resource_type"))
    @Column(name = "team_id", nullable = false)
    private Set<Long> teamIds = new LinkedHashSet<>();

    @Column(name = "updated_by")
    private Long updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static CreationPolicy of(ResourceKind kind) {
        if (kind == ResourceKind.GLOBAL) throw new IllegalArgumentException("GLOBAL은 생성 대상이 아닙니다");
        CreationPolicy p = new CreationPolicy();
        p.resourceType = kind;
        p.mode = CreationMode.ALL_ACTIVE;
        return p;
    }

    /** TEAMS가 아니면 팀 목록을 비운다 — 다른 모드에 남은 팀이 나중에 모드를 바꿀 때 조용히 되살아나지 않게. */
    public void change(CreationMode mode, Collection<Long> teamIds, long actorId) {
        this.mode = mode;
        this.teamIds.clear();
        if (mode == CreationMode.TEAMS && teamIds != null) this.teamIds.addAll(teamIds);
        this.updatedBy = actorId;
    }
}
