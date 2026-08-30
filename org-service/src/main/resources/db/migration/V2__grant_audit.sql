-- W23 권한 변경 감사.
--
-- wiki-backend가 문서 삭제·제한 변경을 기록하기 시작했지만, **스페이스 권한 부여·회수는
-- 여기(org-service)에서 일어나고** wiki는 그 조작을 보지 못한다. 감사에서 가장 궁금한 것이
-- "누가 이 사람에게 권한을 줬나"인데 정작 그게 빠져 있었다.
--
-- 대상 이름(subject_label)을 함께 저장한다. 사용자가 지워지거나 팀이 사라져도 기록은 읽혀야
-- 하고, id만 남기면 숫자만 보인다.
CREATE TABLE grant_audit (
    id            BIGSERIAL    PRIMARY KEY,
    actor_id      BIGINT       NOT NULL,
    action        VARCHAR(20)  NOT NULL,
    subject_type  VARCHAR(10)  NOT NULL,
    subject_id    BIGINT       NOT NULL,
    subject_label VARCHAR(255) NOT NULL,
    resource_type VARCHAR(20)  NOT NULL,
    resource_id   VARCHAR(100) NOT NULL DEFAULT '',
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_grant_audit_resource
    ON grant_audit (resource_type, resource_id, created_at DESC, id DESC);
