-- 조직/팀/권한 스키마. 싱글 테넌트 전제(15번 문서 확정) — organization 테이블 없음.
CREATE TABLE member (
    id           BIGINT PRIMARY KEY,                 -- auth-server user id(JWT sub) 그대로
    display_name VARCHAR(255) NOT NULL,
    email        VARCHAR(255),
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE team (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_member (
    id         BIGSERIAL PRIMARY KEY,
    team_id    BIGINT NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    member_id  BIGINT NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL,                 -- LEAD | MEMBER
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (team_id, member_id)
);
CREATE INDEX idx_team_member_member ON team_member (member_id);

-- 권한 부여 단일 원장. "grant"는 SQL 예약어라 grant_entry.
-- subject_id는 USER/TEAM 다형이라 FK 없음(의도).
CREATE TABLE grant_entry (
    id            BIGSERIAL PRIMARY KEY,
    subject_type  VARCHAR(10) NOT NULL,              -- USER | TEAM
    subject_id    BIGINT      NOT NULL,
    resource_type VARCHAR(20) NOT NULL,              -- GLOBAL | SPACE | PROJECT
    resource_id   VARCHAR(100) NOT NULL DEFAULT '',  -- GLOBAL이면 ''
    role          VARCHAR(20) NOT NULL,              -- VIEWER | EDITOR | ADMIN
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (subject_type, subject_id, resource_type, resource_id)
);
CREATE INDEX idx_grant_resource ON grant_entry (resource_type, resource_id);
CREATE INDEX idx_grant_subject ON grant_entry (subject_type, subject_id);
