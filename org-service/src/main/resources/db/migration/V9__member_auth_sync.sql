-- P-02: 구성원 비활성(정지·퇴사)을 auth-server에 전파한다.
--
-- auth_synced_enabled = auth-server의 users.enabled에 "마지막으로 반영했다고 확인된 값".
-- 원하는 값은 status에서 나온다(SUSPENDED·DEACTIVATED → false, 그 밖 → true). 둘이 어긋난 행을
-- AuthAccountSyncWorker가 주기적으로 찾아 auth-server 내부 API로 보내고, 성공하면 이 값을 맞춘다.
-- 상태를 바꾸는 경로(관리 화면·재초대·에이전트 재등록 등)를 하나하나 훅으로 잡지 않아도 되고,
-- auth-server가 잠깐 죽어 있어도 다음 주기에 다시 맞춘다(유실 없음).
--
-- 기존 행은 true로 시작한다 — auth-server는 지금까지 아무도 비활성으로 만든 적이 없다(users.enabled 전부 true).
-- 그래서 이미 정지·퇴사한 구성원도 이 마이그레이션 직후 첫 주기에 전파된다(소급 적용).
ALTER TABLE member ADD COLUMN auth_synced_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 워커의 폴링 질의(어긋난 행만)가 표 전체를 훑지 않게 하는 부분 인덱스. 평소엔 비어 있다.
CREATE INDEX ix_member_auth_sync_pending ON member (id)
    WHERE (status IN ('SUSPENDED', 'DEACTIVATED')) = auth_synced_enabled;
