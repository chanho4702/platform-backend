-- 사용자 수명주기(U1). 기존 행은 전부 ACTIVE·LEGACY 그대로 — 이미 쓰고 있는 계정을 승인 대기로 되돌리지 않는다.
ALTER TABLE member ADD COLUMN joined_via     VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE member ADD COLUMN approved_by    BIGINT;
ALTER TABLE member ADD COLUMN approved_at    TIMESTAMPTZ;
ALTER TABLE member ADD COLUMN suspended_at   TIMESTAMPTZ;
ALTER TABLE member ADD COLUMN deactivated_at TIMESTAMPTZ;

ALTER TABLE member ADD CONSTRAINT ck_member_status
    CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'));
ALTER TABLE member ADD CONSTRAINT ck_member_joined_via
    CHECK (joined_via IN ('INVITE', 'APPROVAL', 'BOOTSTRAP', 'LEGACY'));

-- 초대·상태 이력. 권한 이력은 기존 grant_audit이 맡는다 — 둘을 한 테이블에 섞지 않는다.
-- member_id는 초대 생성 시점에 없다(그 사람의 계정이 아직 없다). 그래서 둘 다 nullable이고
-- 최소 하나는 있어야 한다는 제약만 건다.
CREATE TABLE member_event (
    id            BIGSERIAL    PRIMARY KEY,
    member_id     BIGINT,
    invitation_id BIGINT,
    type          VARCHAR(32)  NOT NULL,
    actor_id      BIGINT,
    detail        VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_member_event_target CHECK (member_id IS NOT NULL OR invitation_id IS NOT NULL)
);
CREATE INDEX idx_member_event_member ON member_event (member_id, created_at DESC, id DESC);
CREATE INDEX idx_member_event_invitation ON member_event (invitation_id, created_at DESC, id DESC);
