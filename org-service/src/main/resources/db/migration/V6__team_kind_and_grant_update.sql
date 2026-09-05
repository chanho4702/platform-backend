-- "전체 구성원" 팀(U1) — 활성 사람 멤버 전원이 자동으로 속한다. 스페이스·프로젝트 ADMIN이
-- 이 팀에 VIEWER를 주면 그것이 곧 "공개"다. 수동 가입·탈퇴는 서비스가 400으로 막는다.
ALTER TABLE team ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE team ADD CONSTRAINT ck_team_kind CHECK (kind IN ('STANDARD', 'EVERYONE'));

-- PATCH(역할 변경) 이력용. 기존 행은 갱신된 적이 없으므로 NULL이 맞다.
ALTER TABLE grant_entry ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE grant_entry ADD COLUMN updated_by BIGINT;

-- 이름도 함께 본다: 같은 이름의 STANDARD 팀이 이미 있으면 UNIQUE(name)에 걸려 마이그레이션이 죽는다.
INSERT INTO team (name, description, kind)
SELECT '전체 구성원', '모든 활성 구성원이 자동으로 속하는 팀', 'EVERYONE'
 WHERE NOT EXISTS (SELECT 1 FROM team WHERE kind = 'EVERYONE' OR name = '전체 구성원');

-- 기존 활성 사람 멤버를 한 번에 넣는다. 이후의 가입·승인은 서비스가 넣는다.
INSERT INTO team_member (team_id, member_id, role)
SELECT t.id, m.id, 'MEMBER'
  FROM team t
  CROSS JOIN member m
 WHERE t.kind = 'EVERYONE'
   AND m.status = 'ACTIVE'
   AND m.kind = 'HUMAN'
   AND NOT EXISTS (SELECT 1 FROM team_member tm WHERE tm.team_id = t.id AND tm.member_id = m.id);
