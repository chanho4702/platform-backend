package com.platform.searchservice.event;

import com.platform.proto.events.v1.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 공용 스트림 이벤트를 소유 도메인의 색인기로 분배한다. */
@Component
@RequiredArgsConstructor
public class PlatformEventIndexer {

    private final WikiEventIndexer wiki;
    private final AlmEventIndexer alm;

    public void handle(EventEnvelope event) {
        switch (event.getPayloadCase()) {
            case SPACE_CREATED, SPACE_UPDATED, SPACE_DELETED,
                    PAGE_CREATED, PAGE_UPDATED, PAGE_DELETED,
                    ATTACHMENT_ADDED, ATTACHMENT_DELETED -> wiki.handle(event);
            case PROJECT_CREATED, PROJECT_UPDATED, PROJECT_DELETED,
                    ISSUE_CREATED, ISSUE_UPDATED, ISSUE_DELETED -> alm.handle(event);
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException(
                    "payload가 없는 EventEnvelope: eventId=" + event.getEventId());
        }
    }
}
