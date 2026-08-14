package com.platform.searchservice.reindex;

/**
 * 재색인 잡의 상태.
 *
 * SUCCEEDED는 **두 백필이 모두 끝나고 별칭 전환까지 마쳤을 때만** 붙는다 — 부분 완료를 성공으로
 * 보고하면 운영자가 "다 됐다"로 읽고 구 인덱스를 지운다.
 */
public enum ReindexState {
    RUNNING,
    SUCCEEDED,
    FAILED
}
