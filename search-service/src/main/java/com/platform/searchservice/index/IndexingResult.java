package com.platform.searchservice.index;

/**
 * 외부 버전 쓰기의 결과.
 *
 * 같은 이벤트 재전달과 순서가 뒤집힌 과거 이벤트는 모두 OpenSearch 409로 나타난다.
 * 둘 다 이미 더 새롭거나 같은 상태가 반영됐다는 뜻이므로 소비자는 ACK해도 된다.
 */
public enum IndexingResult {
    APPLIED,
    VERSION_CONFLICT
}
