package com.platform.searchservice.content;

import com.platform.proto.alm.v1.IssueContent;

import java.util.Optional;
import java.util.function.Consumer;

/** alm-backend 이슈 조달 창구. 이벤트에는 본문을 싣지 않고 이 계약으로 정본을 조회한다. */
public interface AlmContentClient {

    Optional<IssueContent> getIssue(long issueId);

    /** projectId=0이면 모든 프로젝트의 이슈를 스트리밍한다. */
    void streamIssues(long projectId, Consumer<IssueContent> consumer);
}
