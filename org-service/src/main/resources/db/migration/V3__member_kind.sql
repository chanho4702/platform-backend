-- 에이전트 페르소나를 멤버로 구분(스펙 D6). 기존 행은 전부 사람.
ALTER TABLE member ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'HUMAN';
