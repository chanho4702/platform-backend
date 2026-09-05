package com.platform.orgservice.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "team")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {

    /** 시드 이름. 화면이 이 문자열을 그대로 보여준다. */
    public static final String EVERYONE_NAME = "전체 구성원";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** STANDARD | EVERYONE — EVERYONE은 이름 변경·삭제·수동 가입이 막힌다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TeamKind kind;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Team of(String name, String description) {
        Team t = new Team();
        t.name = name;
        t.description = description;
        t.kind = TeamKind.STANDARD;
        return t;
    }

    public static Team everyone() {
        Team t = of(EVERYONE_NAME, "모든 활성 구성원이 자동으로 속하는 팀");
        t.kind = TeamKind.EVERYONE;
        return t;
    }

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public boolean isEveryone() { return kind == TeamKind.EVERYONE; }
}
