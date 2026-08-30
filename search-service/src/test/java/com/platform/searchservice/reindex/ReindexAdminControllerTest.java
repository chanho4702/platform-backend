package com.platform.searchservice.reindex;

import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.searchservice.permission.PermissionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재색인 관리 REST의 인가·상태 계약.
 *
 * 경로는 서비스 내부 기준(`/admin/reindex`)이다 — 게이트웨이가 `Path=/api/search/**` +
 * `StripPrefix=2`로 접두사를 떼고 넘긴다(외부 노출 경로는 `/api/search/admin/reindex`).
 */
@SpringBootTest
@ActiveProfiles("test")
class ReindexAdminControllerTest {

    private static final long ADMIN = 1L;
    private static final long MEMBER = 2L;

    @Autowired WebApplicationContext context;

    // 재색인 자체는 Testcontainers 통합 테스트가 실제 OpenSearch에 대고 검증한다.
    // 여기서 보는 것은 그 앞단의 인가·상태 계약이라 잡 실행기는 대역으로 세운다.
    @MockitoBean ReindexService reindex;
    @MockitoBean PermissionClient permissions;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void GLOBAL_ADMIN이_재색인을_시작하면_202와_jobId를_받는다() throws Exception {
        given(permissions.isGlobalAdmin(ADMIN)).willReturn(true);
        given(reindex.start()).willReturn(running("job-1"));

        mvc.perform(post("/admin/reindex").with(asUser(ADMIN)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.aliasSwitched").value(false));
    }

    @Test
    void GLOBAL_ADMIN이_아니면_403이고_재색인은_시작되지_않는다() throws Exception {
        given(permissions.isGlobalAdmin(MEMBER)).willReturn(false);

        mvc.perform(post("/admin/reindex").with(asUser(MEMBER)))
                .andExpect(status().isForbidden());

        verify(reindex, never()).start();
    }

    /**
     * 현황 조회는 관리 화면이 뜰 때 부르고, 동시에 **전역 관리자 여부를 확인하는 창구**다 —
     * 403이면 화면이 관리 메뉴 자체를 감춘다. 그래서 이 경로의 인가가 다른 경로와 같아야 한다.
     */
    @Test
    void GLOBAL_ADMIN은_색인_현황을_받는다() throws Exception {
        given(permissions.isGlobalAdmin(ADMIN)).willReturn(true);
        given(reindex.indexStatus()).willReturn(new ReindexStatusView(
                "wiki-page-v5", "wiki-attachment-v5", 22L, 3L, null));

        mvc.perform(get("/admin/reindex/status").with(asUser(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageIndex").value("wiki-page-v5"))
                .andExpect(jsonPath("$.pageDocs").value(22))
                .andExpect(jsonPath("$.runningJob").doesNotExist());
    }

    @Test
    void GLOBAL_ADMIN이_아니면_색인_현황도_403이다() throws Exception {
        given(permissions.isGlobalAdmin(MEMBER)).willReturn(false);

        mvc.perform(get("/admin/reindex/status").with(asUser(MEMBER)))
                .andExpect(status().isForbidden());

        verify(reindex, never()).indexStatus();
    }

    @Test
    void org_service가_불능이면_403이_아니라_503이다() throws Exception {
        // 권한을 "모른다"를 "없다"로 바꾸면 관리자가 장애 중에 조용히 거부당한다.
        willThrow(new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다"))
                .given(permissions).isGlobalAdmin(ADMIN);

        mvc.perform(post("/admin/reindex").with(asUser(ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("권한 서비스에 연결할 수 없습니다"));

        verify(reindex, never()).start();
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(post("/admin/reindex"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 이미_실행_중이면_409다() throws Exception {
        given(permissions.isGlobalAdmin(ADMIN)).willReturn(true);
        willThrow(new ConflictException("재색인이 이미 실행 중입니다: jobId=job-1"))
                .given(reindex).start();

        mvc.perform(post("/admin/reindex").with(asUser(ADMIN)))
                .andExpect(status().isConflict());
    }

    @Test
    void 상태_조회도_GLOBAL_ADMIN만_가능하다() throws Exception {
        given(permissions.isGlobalAdmin(MEMBER)).willReturn(false);

        mvc.perform(get("/admin/reindex/job-1").with(asUser(MEMBER)))
                .andExpect(status().isForbidden());

        verify(reindex, never()).status(anyString());
    }

    @Test
    void 모르는_jobId는_404다() throws Exception {
        given(permissions.isGlobalAdmin(ADMIN)).willReturn(true);
        willThrow(new NotFoundException("재색인 잡을 찾을 수 없습니다: jobId=없는-잡"))
                .given(reindex).status("없는-잡");

        mvc.perform(get("/admin/reindex/없는-잡").with(asUser(ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 완료된_잡_상태에는_새_인덱스와_건수가_담긴다() throws Exception {
        given(permissions.isGlobalAdmin(ADMIN)).willReturn(true);
        given(reindex.status("job-1")).willReturn(new ReindexJobView(
                "job-1", ReindexState.SUCCEEDED, true, 12L, 3L,
                "wiki-page-v2", "wiki-attachment-v2",
                Instant.parse("2026-08-14T00:00:00Z"), Instant.parse("2026-08-14T00:01:00Z"), null));

        mvc.perform(get("/admin/reindex/job-1").with(asUser(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.aliasSwitched").value(true))
                .andExpect(jsonPath("$.pagesIndexed").value(12))
                .andExpect(jsonPath("$.attachmentsIndexed").value(3))
                .andExpect(jsonPath("$.pageIndex").value("wiki-page-v2"));
    }

    private static ReindexJobView running(String jobId) {
        return new ReindexJobView(jobId, ReindexState.RUNNING, false, 0L, 0L,
                "wiki-page-v2", "wiki-attachment-v2", Instant.parse("2026-08-14T00:00:00Z"), null, null);
    }

    private static RequestPostProcessor asUser(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)).claim("roles", List.of("USER")));
    }
}
