package com.platform.searchservice.reindex;

import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.searchservice.content.WikiContentClient;
import com.platform.searchservice.index.AttachmentDoc;
import com.platform.searchservice.index.IndexNames;
import com.platform.searchservice.index.OpenSearchIndexFactory;
import com.platform.searchservice.index.OpenSearchIndexService;
import com.platform.searchservice.index.OpenSearchIndexService.VersionedDoc;
import com.platform.searchservice.index.PageDoc;
import com.platform.searchservice.index.WikiDocuments;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 전량 백필/재색인 오케스트레이션 (설계 §9).
 *
 * 흐름: 다음 세대 인덱스 2개 생성 → wiki-backend gRPC 스트림으로 전량 색인 → **둘 다 끝난 뒤에만**
 * 별칭을 원자적으로 전환. 어디서 실패하든 별칭은 구 인덱스에 그대로 남고, 구 인덱스는 지우지
 * 않는다(되돌리기와 대조 확인이 가능해야 한다 — 삭제는 운영자의 수동 조작이다).
 *
 * <p>알려진 경계: 백필이 도는 동안 이벤트 소비자는 <b>별칭</b>으로 쓰므로 그 사이의 갱신은 구
 * 인덱스에만 반영된다. 스트림으로 조달한 뒤 전환 전에 바뀐 문서는 새 인덱스에서 잠깐 스테일할 수
 * 있다. 이중 쓰기 대신 "재색인은 다시 돌리면 되는 조작"이라는 설계 §9의 입장을 그대로 따른다.
 */
@Service
@Slf4j
public class ReindexService {

    /** 전량 백필 — 스페이스 한정은 T9의 관리자 계약이 아니다(클라이언트의 내부 능력으로만 남는다). */
    private static final long ALL_SPACES = 0L;
    private static final int BATCH_SIZE = 500;
    /** 실패한 잡이 남긴 인덱스를 비켜가며 찾을 수 있는 최대 세대 수. */
    private static final int MAX_VERSION_PROBE = 50;

    private final WikiContentClient content;
    private final OpenSearchIndexService indexes;
    private final OpenSearchIndexFactory factory;
    private final Executor executor;

    private final Map<String, ReindexJob> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeJobId = new AtomicReference<>();

    /**
     * 재색인 전용 스레드를 서비스가 직접 소유한다.
     *
     * 스레드가 하나인 것이 두 번째 방어선이다 — {@link #start()}의 가드가 한 번에 한 잡만 받지만,
     * 설령 그게 뚫려도 두 전환이 동시에 실행되지는 않는다. 요청 스레드와 분리하는 이유는 전량
     * 백필이 HTTP 타임아웃보다 오래 걸리기 때문이다.
     *
     * 컨텍스트에 {@code Executor} 빈을 새로 노출하지 않는 것도 의도다 — Boot의 기본
     * {@code applicationTaskExecutor}는 {@code Executor} 빈이 없을 때만 등록되므로, 여기서
     * 빈으로 내놓으면 MVC 비동기 처리의 실행기까지 갈아치우게 된다.
     */
    @Autowired
    public ReindexService(
            WikiContentClient content,
            OpenSearchIndexService indexes,
            OpenSearchIndexFactory factory) {
        this(content, indexes, factory, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "search-reindex");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** 테스트가 실행 시점을 통제할 수 있게 실행기를 주입받는 통로. */
    ReindexService(
            WikiContentClient content,
            OpenSearchIndexService indexes,
            OpenSearchIndexFactory factory,
            Executor executor) {
        this.content = content;
        this.indexes = indexes;
        this.factory = factory;
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    /**
     * 비동기 잡을 띄우고 즉시 현재 상태를 돌려준다.
     *
     * 동시에 두 잡이 돌면 두 전환이 경합해 페이지·첨부 별칭이 서로 다른 세대를 가리킬 수 있다 —
     * 그래서 한 번에 하나만 받는다.
     */
    public ReindexJobView start() {
        String jobId = UUID.randomUUID().toString();
        String active = activeJobId.compareAndExchange(null, jobId);
        if (active != null) {
            throw new ConflictException("재색인이 이미 실행 중입니다: jobId=" + active);
        }

        ReindexJob job;
        try {
            Targets targets = resolveTargets();
            job = new ReindexJob(jobId, targets.pageIndex(), targets.attachmentIndex(), Instant.now());
            jobs.put(jobId, job);
            // 여기서 거부당하면(RejectedExecutionException 등) 잡은 시작조차 못 한 것이다.
            executor.execute(() -> run(job, targets));
        } catch (RuntimeException e) {
            // 등록만 되고 돌지 않은 잡을 남기면 영원히 RUNNING인 유령이 된다 — 가드가 풀려 다음
            // 재색인은 받아지는데, 이 잡만 끝나지 않는 상태로 조회된다. 호출자는 예외로 끝나
            // jobId조차 받지 못하므로 남겨둘 이유도 없다.
            jobs.remove(jobId);
            activeJobId.compareAndSet(jobId, null);
            log.error("재색인을 시작하지 못했습니다: jobId={}", jobId, e);
            throw e;
        }
        return job.view();
    }

    /** 테스트 전용 — 시작에 실패한 잡이 조회 가능한 채로 남지 않는지 확인하는 관측점. */
    int trackedJobCount() {
        return jobs.size();
    }

    /**
     * 색인 현황 — 관리 화면이 "지금 무엇이 서비스 중이고 몇 건인가"를 보여준다.
     *
     * 이 값들이 필요한 이유는 재색인이 필요한지 판단할 근거가 달리 없기 때문이다. 색인에 필드를
     * 더한 배포 뒤에 세대가 그대로면 새 필드는 비어 있고, 검색 필터는 조용히 0건을 낸다.
     */
    public ReindexStatusView indexStatus() {
        String pageIndex = indexes.resolveAliasIndex(IndexNames.PAGE_ALIAS);
        String attachmentIndex = indexes.resolveAliasIndex(IndexNames.ATTACHMENT_ALIAS);
        String running = activeJobId.get();
        return new ReindexStatusView(
                pageIndex,
                attachmentIndex,
                indexes.documentCount(pageIndex),
                indexes.documentCount(attachmentIndex),
                running == null ? null : status(running));
    }

    public ReindexJobView status(String jobId) {
        ReindexJob job = jobs.get(jobId);
        if (job == null) {
            throw new NotFoundException("재색인 잡을 찾을 수 없습니다: jobId=" + jobId);
        }
        return job.view();
    }

    private void run(ReindexJob job, Targets targets) {
        // 조달한 문서에 타임스탬프가 없을 때 쓸 외부 버전. 잡 시작 시각이면 그보다 오래된
        // 이벤트에 덮이지 않으면서 이후 갱신은 정상적으로 이긴다.
        long fallbackVersion = System.currentTimeMillis();
        try {
            factory.createIndex(targets.pageIndex(), OpenSearchIndexFactory.PAGE_MAPPING);
            factory.createIndex(targets.attachmentIndex(), OpenSearchIndexFactory.ATTACHMENT_MAPPING);

            backfillPages(job, targets.pageIndex(), fallbackVersion);
            backfillAttachments(job, targets.attachmentIndex(), fallbackVersion);
            indexes.refresh(targets.pageIndex(), targets.attachmentIndex());

            // 여기까지 와야 전환한다 — 하나라도 실패했으면 별칭은 손대지 않는다.
            indexes.switchAliases(Map.of(
                    IndexNames.PAGE_ALIAS, targets.pageIndex(),
                    IndexNames.ATTACHMENT_ALIAS, targets.attachmentIndex()));

            job.succeeded(Instant.now());
            ReindexJobView view = job.view();
            log.info("재색인 완료: jobId={} pages={} attachments={} pageIndex={} attachmentIndex={}",
                    view.jobId(), view.pagesIndexed(), view.attachmentsIndexed(),
                    view.pageIndex(), view.attachmentIndex());
        } catch (Exception e) {
            job.failed(Instant.now(), e.getMessage());
            // 색인 실패는 화면이 멀쩡해서 티가 안 난다(08-02 교훈) — WARN이 아니라 ERROR다.
            log.error("재색인 실패 — 별칭은 구 인덱스에 그대로 둔다. 새 인덱스는 별칭 없이 남으므로 "
                            + "확인 후 수동 삭제: jobId={} pageIndex={} attachmentIndex={}",
                    job.jobId(), targets.pageIndex(), targets.attachmentIndex(), e);
        } finally {
            activeJobId.compareAndSet(job.jobId(), null);
        }
    }

    private void backfillPages(ReindexJob job, String physicalIndex, long fallbackVersion) {
        List<VersionedDoc<PageDoc>> buffer = new ArrayList<>(BATCH_SIZE);
        content.streamPages(ALL_SPACES, page -> {
            buffer.add(new VersionedDoc<>(
                    WikiDocuments.toDocument(page), externalVersion(page.getUpdatedAt(), fallbackVersion)));
            if (buffer.size() >= BATCH_SIZE) {
                flushPages(job, physicalIndex, buffer);
            }
        });
        flushPages(job, physicalIndex, buffer);
    }

    private void backfillAttachments(ReindexJob job, String physicalIndex, long fallbackVersion) {
        List<VersionedDoc<AttachmentDoc>> buffer = new ArrayList<>(BATCH_SIZE);
        content.streamAttachments(ALL_SPACES, attachment -> {
            buffer.add(new VersionedDoc<>(
                    WikiDocuments.toDocument(attachment),
                    externalVersion(attachment.getCreatedAt(), fallbackVersion)));
            if (buffer.size() >= BATCH_SIZE) {
                flushAttachments(job, physicalIndex, buffer);
            }
        });
        flushAttachments(job, physicalIndex, buffer);
    }

    private void flushPages(ReindexJob job, String physicalIndex, List<VersionedDoc<PageDoc>> buffer) {
        if (buffer.isEmpty()) return;
        indexes.bulkIndexPages(physicalIndex, List.copyOf(buffer));
        job.addPages(buffer.size());
        buffer.clear();
    }

    private void flushAttachments(
            ReindexJob job, String physicalIndex, List<VersionedDoc<AttachmentDoc>> buffer) {
        if (buffer.isEmpty()) return;
        indexes.bulkIndexAttachments(physicalIndex, List.copyOf(buffer));
        job.addAttachments(buffer.size());
        buffer.clear();
    }

    /** 외부 버전은 양수여야 한다 — 원본에 시각이 없으면 잡 시작 시각으로 대신한다. */
    private static long externalVersion(long timestamp, long fallback) {
        return timestamp > 0 ? timestamp : fallback;
    }

    /**
     * 지금 별칭이 가리키는 세대에서 다음 세대 이름을 정한다.
     *
     * 페이지와 첨부의 세대 번호를 맞춰 둔다(둘 다 v3) — 운영자가 "이번 재색인이 만든 인덱스"를
     * 이름만 보고 짝지을 수 있어야 한다. 이미 있는 이름은 지우지 않고 비켜간다: 실패한 잡이
     * 남긴 인덱스일 뿐인지, 누가 쓰고 있는지 여기서 알 수 없기 때문이다.
     */
    private Targets resolveTargets() {
        String currentPage = indexes.resolveAliasIndex(IndexNames.PAGE_ALIAS);
        String currentAttachment = indexes.resolveAliasIndex(IndexNames.ATTACHMENT_ALIAS);
        int next;
        try {
            next = Math.max(
                    IndexNames.versionOf(currentPage, IndexNames.PAGE_ALIAS),
                    IndexNames.versionOf(currentAttachment, IndexNames.ATTACHMENT_ALIAS)) + 1;
        } catch (IllegalArgumentException e) {
            // 요청이 잘못된 게 아니라 클러스터 상태가 관례를 벗어난 것이다 — 400으로 나가면 오해된다.
            throw new IllegalStateException("현재 색인 세대를 읽지 못해 재색인을 시작할 수 없습니다", e);
        }

        try {
            for (int probe = 0; probe < MAX_VERSION_PROBE; probe++, next++) {
                String pageIndex = IndexNames.versioned(IndexNames.PAGE_ALIAS, next);
                String attachmentIndex = IndexNames.versioned(IndexNames.ATTACHMENT_ALIAS, next);
                if (!factory.indexExists(pageIndex) && !factory.indexExists(attachmentIndex)) {
                    return new Targets(pageIndex, attachmentIndex);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("다음 세대 인덱스 이름을 확인하지 못했습니다", e);
        }
        throw new IllegalStateException(
                "비어 있는 다음 세대 인덱스 이름을 찾지 못했습니다 — 실패한 재색인이 남긴 인덱스를 정리하세요");
    }

    private record Targets(String pageIndex, String attachmentIndex) {}
}
