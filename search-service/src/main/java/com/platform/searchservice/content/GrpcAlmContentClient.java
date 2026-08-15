package com.platform.searchservice.content;

import com.platform.proto.alm.v1.AlmContentServiceGrpc;
import com.platform.proto.alm.v1.GetIssueContentRequest;
import com.platform.proto.alm.v1.IssueContent;
import com.platform.proto.alm.v1.ListIssueContentsRequest;
import com.platform.searchservice.common.ServiceUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;

import java.util.Iterator;
import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class GrpcAlmContentClient implements AlmContentClient {

    private final AlmContentServiceGrpc.AlmContentServiceBlockingStub stub;

    @Override
    public Optional<IssueContent> getIssue(long issueId) {
        try {
            return Optional.of(stub.getIssueContent(
                    GetIssueContentRequest.newBuilder().setIssueId(issueId).build()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) return Optional.empty();
            throw unavailable("ALM 이슈 조달 실패: issue=" + issueId, e);
        }
    }

    @Override
    public void streamIssues(long projectId, Consumer<IssueContent> consumer) {
        try {
            Iterator<IssueContent> issues = stub.listIssueContents(
                    ListIssueContentsRequest.newBuilder().setProjectId(projectId).build());
            while (issues.hasNext()) consumer.accept(issues.next());
        } catch (StatusRuntimeException e) {
            throw unavailable("ALM 이슈 백필 스트림 중단: project=" + projectId, e);
        }
    }

    private static ServiceUnavailableException unavailable(String message, StatusRuntimeException cause) {
        return new ServiceUnavailableException(message + " status=" + cause.getStatus().getCode(), cause);
    }
}
