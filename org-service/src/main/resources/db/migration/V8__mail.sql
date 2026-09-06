-- 플랫폼 메일 허브(M1). 발송은 org-service 한 곳에서만 한다 — wiki·alm은 내부 API로 넘긴다.
-- 설정·자격증명·재시도·발송 로그가 한 곳에 모여야 "메일이 안 갔다"를 한 화면에서 설명할 수 있다.

-- 설정은 언제나 한 행(id=1)이다. 여러 프로필을 두지 않는 이유는 소비자가 "지금 설정"만 묻기 때문이다 —
-- 행이 둘 이상이면 어느 것으로 보냈는지가 로그와 어긋난다.
CREATE TABLE mail_setting (
    id           BIGINT       PRIMARY KEY,
    enabled      BOOLEAN      NOT NULL DEFAULT false,
    host         VARCHAR(255),
    port         INT          NOT NULL DEFAULT 587,
    username     VARCHAR(320),
    -- AES-GCM 암호문(base64: iv 12바이트 || 암호문+태그). 키는 ORG_SETTINGS_ENC_KEY(32바이트 hex).
    -- 평문으로 두지 않는 이유는 이 DB 덤프가 곧 SMTP 계정이 되기 때문이다.
    password_enc TEXT,
    tls          VARCHAR(16)  NOT NULL DEFAULT 'STARTTLS'
                 CHECK (tls IN ('NONE', 'STARTTLS', 'SSL')),
    from_address VARCHAR(320),
    from_name    VARCHAR(120),
    -- 감사: 마지막으로 바꾼 사람. 최초 시드(MAIL_SEED_*)면 NULL이다.
    updated_by   BIGINT,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_mail_setting_singleton CHECK (id = 1)
);

-- 발송 큐 겸 로그. 보내기를 호출 트랜잭션 안에서 하지 않는 이유는, 초대 생성이 SMTP 지연에 묶이고
-- 발송 실패가 초대 자체를 되돌리기 때문이다. 여기 행이 남으면 초대는 이미 원장에 있고 배달만 남는다.
CREATE TABLE mail_outbox (
    id              BIGSERIAL   PRIMARY KEY,
    to_address      VARCHAR(320) NOT NULL,
    subject         VARCHAR(500) NOT NULL,
    body_text       TEXT        NOT NULL,
    body_html       TEXT,
    -- 누가 넣었는가 — wiki | alm | org | test. 로그 화면의 출처 열이고, 장애 때 범위를 가른다.
    source          VARCHAR(32) NOT NULL,
    status          VARCHAR(16) NOT NULL
                    CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    -- 지수 백오프의 다음 시각. PENDING이어도 이 시각 전에는 집지 않는다.
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

-- 워커가 집는 조건 그대로(status + next_attempt_at). 큐가 길어져도 스캔하지 않는다.
CREATE INDEX idx_mail_outbox_due ON mail_outbox (status, next_attempt_at);
-- 로그 화면은 최신순이다.
CREATE INDEX idx_mail_outbox_created ON mail_outbox (created_at DESC, id DESC);
