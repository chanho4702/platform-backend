package com.platform.searchservice.event;

import com.platform.proto.alm.v1.IssueContent;
import com.platform.proto.events.v1.EventEnvelope;
import com.platform.proto.events.v1.IssueCreated;
import com.platform.proto.events.v1.ProjectDeleted;
import com.platform.searchservice.content.AlmContentClient;
import com.platform.searchservice.index.AlmDocuments;
import com.platform.searchservice.index.OpenSearchIndexService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlmEventIndexerTest {

    @Test
    void 이슈_생성은_정본을_조달해_외부_버전으로_색인한다() {
        AlmContentClient content = mock(AlmContentClient.class);
        OpenSearchIndexService indexes = mock(OpenSearchIndexService.class);
        IssueContent issue = issue(71L, 7L);
        when(content.getIssue(71L)).thenReturn(Optional.of(issue));

        new AlmEventIndexer(content, indexes).handle(EventEnvelope.newBuilder()
                .setEventId("event-1")
                .setOccurredAt(5_000L)
                .setIssueCreated(IssueCreated.newBuilder()
                        .setIssueId(71L).setProjectId(7L).setIssueKey("ALM-1").setTitle("검색"))
                .build());

        verify(indexes).upsertIssue(AlmDocuments.toDocument(issue), 5_000L);
    }

    @Test
    void 프로젝트_삭제는_자식_이슈를_한꺼번에_정리한다() {
        AlmContentClient content = mock(AlmContentClient.class);
        OpenSearchIndexService indexes = mock(OpenSearchIndexService.class);

        new AlmEventIndexer(content, indexes).handle(EventEnvelope.newBuilder()
                .setEventId("event-2")
                .setOccurredAt(6_000L)
                .setProjectDeleted(ProjectDeleted.newBuilder().setProjectId(7L))
                .build());

        verify(indexes).deleteIssuesByProjectId(7L);
    }

    private static IssueContent issue(long issueId, long projectId) {
        return IssueContent.newBuilder()
                .setIssueId(issueId)
                .setProjectId(projectId)
                .setProjectKey("ALM")
                .setProjectName("플랫폼 ALM")
                .setIssueKey("ALM-1")
                .setTitle("검색")
                .setDescription("통합 검색")
                .setType("TASK")
                .setStatus("TODO")
                .setPriority("MEDIUM")
                .setReporterId(1L)
                .setVersion(1)
                .setUpdatedAt(4_000L)
                .build();
    }
}
