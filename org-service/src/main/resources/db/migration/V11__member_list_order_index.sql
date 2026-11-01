-- 구성원 목록·CSV 내보내기(F-25)의 정렬 인덱스.
--
-- 목록(/members, /members/page)과 내보내기(/members/export)는 모두 status·kind로 거르고
-- display_name, id 순으로 정렬한다(MemberAdminService.specFor). 지금까지는 member 전체를 훑고 정렬했다 —
-- 기본 필터(ACTIVE·HUMAN)는 거의 모든 행이라 내보내기에서 정렬 비용이 그대로 남는다.
-- 등치 조건 열을 앞에, 정렬 열을 뒤에 둬서 인덱스 순서로 바로 읽게 한다. 검색어(q)의 부분 일치(LIKE '%..%')는
-- B-tree로 못 줄이므로 여기서 다루지 않는다.
CREATE INDEX idx_member_status_kind_name ON member (status, kind, display_name, id);
