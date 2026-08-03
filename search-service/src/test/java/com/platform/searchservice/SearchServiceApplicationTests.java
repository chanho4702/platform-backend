package com.platform.searchservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SearchServiceApplicationTests {

    @Test
    void contextLoads() {
        // 빈 구성이 서로 맞물리는지만 본다 — 외부 의존(OpenSearch·Redis·gRPC)은 여기서 뜨지 않는다
    }
}
