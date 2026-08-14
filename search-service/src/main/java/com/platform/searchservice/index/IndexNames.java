package com.platform.searchservice.index;

/**
 * 인덱스와 읽기 별칭.
 *
 * 별칭을 두는 이유는 **무중단 재색인**이다 — 새 인덱스(`...-v2`)에 전부 채운 뒤 별칭만
 * 원자적으로 옮기면 검색이 끊기지 않는다. 코드는 항상 별칭으로만 읽고 쓴다.
 *
 * 페이지와 첨부를 나눈 이유: 필드가 다르고(제목/본문 vs 파일명) 갱신 주기도 다르다.
 * 질의는 두 별칭을 함께 지정한다. Wave D의 ALM 인덱스는 별칭 추가로 붙인다.
 */
public final class IndexNames {

    private IndexNames() {}

    public static final String PAGE_ALIAS = "wiki-page";
    public static final String ATTACHMENT_ALIAS = "wiki-attachment";

    /** 최초 생성 시의 물리 인덱스 이름. 재색인은 뒤 숫자를 올린다. */
    public static final String PAGE_INDEX_V1 = PAGE_ALIAS + "-v1";
    public static final String ATTACHMENT_INDEX_V1 = ATTACHMENT_ALIAS + "-v1";

    /** 검색이 훑는 대상 — 두 별칭을 함께 본다. */
    public static final String[] SEARCH_TARGETS = { PAGE_ALIAS, ATTACHMENT_ALIAS };

    /** `wiki-page` + 2 → `wiki-page-v2`. 재색인이 만드는 다음 세대 인덱스 이름. */
    public static String versioned(String alias, int version) {
        if (version < 1) throw new IllegalArgumentException("인덱스 버전은 1 이상이어야 합니다: " + version);
        return alias + "-v" + version;
    }

    /**
     * `wiki-page-v3` → 3.
     *
     * 관례를 벗어난 이름이면 예외다 — 다음 버전을 추측해서 별칭을 옮기느니 재색인을 실패시키는 쪽이
     * 안전하다(별칭이 엉뚱한 인덱스를 가리키면 검색이 조용히 비어버린다).
     */
    public static int versionOf(String physicalIndex, String alias) {
        String prefix = alias + "-v";
        if (physicalIndex == null || !physicalIndex.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "별칭 " + alias + "의 물리 인덱스 이름이 관례(" + prefix + "N)를 벗어났습니다: " + physicalIndex);
        }
        try {
            return Integer.parseInt(physicalIndex.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "별칭 " + alias + "의 물리 인덱스 버전을 읽을 수 없습니다: " + physicalIndex, e);
        }
    }

    public static String pageDocId(long pageId) {
        return String.valueOf(pageId);
    }

    public static String attachmentDocId(long attachmentId) {
        return String.valueOf(attachmentId);
    }
}
