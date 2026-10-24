-- 스페이스·프로젝트 "만들 수 있는 사람" 전역 설정(권한·계정 점검 Top 5-③, 2026-10-24).
--
-- 지금까지는 로그인만 하면 누구나 스페이스·프로젝트를 만들고 그 ADMIN이 됐다. 승인 대기 계정이 API를 직접
-- 불러 공간을 만든 뒤 ADMIN grant로 초대 권한까지 얻는 우회가 점검에서 확인됐다. 판정은 org가 한다 —
-- wiki·alm은 CheckPermission(GLOBAL, "create:SPACE" | "create:PROJECT")으로 묻는다.
--
-- 행이 없으면 ALL_ACTIVE(활성 구성원 전체 — 컨플루언스 기본과 같음)다. 시드는 넣지 않는다(플랫폼 정책).
CREATE TABLE creation_policy (
    resource_type VARCHAR(16) PRIMARY KEY
                  CHECK (resource_type IN ('SPACE', 'PROJECT')),
    mode          VARCHAR(16) NOT NULL
                  CHECK (mode IN ('ALL_ACTIVE', 'GLOBAL_ADMIN', 'TEAMS')),
    updated_by    BIGINT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- mode=TEAMS일 때 만들 수 있는 팀. 팀이 지워지면 함께 사라진다(빈 목록 TEAMS = 전역 관리자만).
CREATE TABLE creation_policy_team (
    resource_type VARCHAR(16) NOT NULL REFERENCES creation_policy (resource_type) ON DELETE CASCADE,
    team_id       BIGINT      NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    PRIMARY KEY (resource_type, team_id)
);
CREATE INDEX idx_creation_policy_team_team ON creation_policy_team (team_id);
