-- 초대 원장(U1). 초대는 로그인 전 사람을 가리켜야 하는데 그때는 member.id가 없다 —
-- 그래서 키는 이메일이고, 토큰은 링크 검증·login_hint·수락 추적용이다.
CREATE TABLE invitation (
    id                 BIGSERIAL PRIMARY KEY,
    email              VARCHAR(320) NOT NULL,
    -- 대조는 항상 정규화된 값으로 한다(trim + 소문자). 원본 email은 메일 발송·표시용.
    email_norm         VARCHAR(320) NOT NULL,
    -- 원문 토큰은 저장하지 않는다. DB가 새도 초대 링크를 만들 수 없어야 한다.
    token_hash         VARCHAR(64)  NOT NULL UNIQUE,
    status             VARCHAR(16)  NOT NULL
                       CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    invited_by         BIGINT       NOT NULL,
    message            VARCHAR(500),
    expires_at         TIMESTAMPTZ  NOT NULL,
    accepted_member_id BIGINT,
    accepted_at        TIMESTAMPTZ,
    accepted_via       VARCHAR(16)  CHECK (accepted_via IN ('TOKEN', 'EMAIL_MATCH')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 한 이메일에 살아 있는 초대는 하나뿐이다. 새로 보내면 이전 것을 EXPIRED로 눕히고 만든다 —
-- 둘이 살아 있으면 "어느 초대의 팀·권한이 적용됐나"를 나중에 설명할 수 없다.
CREATE UNIQUE INDEX uq_invitation_pending_email ON invitation (email_norm) WHERE status = 'PENDING';
CREATE INDEX idx_invitation_status ON invitation (status, created_at DESC, id DESC);

-- 프리셋: 수락 순간 적용될 팀·권한. 초대 시점에는 member가 없어 team_member/grant_entry를 미리 못 만든다.
CREATE TABLE invitation_team (
    id            BIGSERIAL PRIMARY KEY,
    invitation_id BIGINT      NOT NULL REFERENCES invitation (id) ON DELETE CASCADE,
    team_id       BIGINT      NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    role          VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    UNIQUE (invitation_id, team_id)
);

CREATE TABLE invitation_grant (
    id            BIGSERIAL    PRIMARY KEY,
    invitation_id BIGINT       NOT NULL REFERENCES invitation (id) ON DELETE CASCADE,
    -- grant_entry.resource_type/resource_id와 같은 표현을 쓴다(GLOBAL이면 '').
    scope         VARCHAR(20)  NOT NULL,
    resource_id   VARCHAR(100) NOT NULL DEFAULT '',
    role          VARCHAR(20)  NOT NULL,
    UNIQUE (invitation_id, scope, resource_id)
);
