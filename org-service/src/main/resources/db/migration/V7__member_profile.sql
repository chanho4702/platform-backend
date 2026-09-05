-- 멤버 프로필(현재는 아바타뿐). member 테이블을 넓히지 않고 별도 테이블로 둔다 —
-- 아바타는 org의 권한·조직 모델과 수명이 다르고, member는 auth-server 클레임의 미러라
-- JIT 미러링이 건드리는 행에 사용자가 올린 자산을 섞지 않는다.
CREATE TABLE member_profile (
    member_id           BIGINT PRIMARY KEY REFERENCES member(id) ON DELETE CASCADE,
    -- 아바타 오브젝트 키(avatars/{memberId}/{uuid}.{ext}). 바이트는 S3 호환 저장소 또는 로컬 파일에 있다.
    avatar_key          VARCHAR(200),
    -- 업로드 때 매직 바이트로 판별한 타입. 읽을 때 이 값을 그대로 Content-Type으로 돌려준다.
    avatar_content_type VARCHAR(80),
    -- 캐시 무효화용(프론트가 ?v=로 붙인다). 프로필 전체의 updated_at과 분리해야
    -- 아바타와 무관한 프로필 갱신이 이미지 URL을 흔들지 않는다.
    avatar_updated_at   TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
