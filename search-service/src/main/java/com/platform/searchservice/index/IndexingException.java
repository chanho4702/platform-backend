package com.platform.searchservice.index;

/** OpenSearch가 정상 처리하지 못한 색인 연산 — 소비자가 재시도해야 한다. */
public class IndexingException extends RuntimeException {

    public IndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
