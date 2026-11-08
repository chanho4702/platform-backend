package com.platform.orgservice.config;

import java.util.LinkedHashMap;

/**
 * 인바운드 {@code /internal/org/**}의 용도별 토큰 집합(N5, 2026-11-08) — 경로 → 허용 토큰 목록.
 *
 * <table>
 *   <tr><th>집합</th><th>경로</th><th>발신</th><th>env</th></tr>
 *   <tr><td>agents</td><td>{@code members/agents/**}</td><td>agent-service</td><td>{@code ORG_AGENT_TOKEN}</td></tr>
 *   <tr><td>mail</td><td>{@code mail/**}</td><td>wiki·alm·agent-service</td><td>{@code ORG_MAIL_TOKEN} + {@code ORG_AGENT_TOKEN}</td></tr>
 *   <tr><td>identity</td><td>{@code invitations/**}·{@code members/ensure}·{@code members/*}{@code /authz}</td><td>auth-server</td><td>{@code ORG_IDENTITY_TOKEN}</td></tr>
 * </table>
 *
 * <p><b>폴백은 여기(코드)서 한다.</b> 새 값이 없거나 <b>빈 문자열</b>이면 옛 공유 토큰({@code ORG_INTERNAL_TOKEN})을
 * 쓴다. yml의 {@code ${NEW:${OLD:}}}는 NEW가 빈 문자열로 들어오면(compose {@code ${NEW:-}}) 폴백하지 않아 그 경로가
 * 조용히 닫힌다. mail 집합은 두 변수 각각을 폴백한다 — AGENT만 먼저 분리해도 위키·ALM은 아직 옛 값을 보내기 때문이다.
 *
 * <p>순서가 의미 있다(처음 맞는 패턴). agents를 {@code members/*}{@code /authz}보다 앞에 둔다. 새 내부 경로를 만들면
 * 여기에 넣어야 열린다 — 어느 패턴에도 안 맞으면 403이다.
 */
public final class InternalTokenRoutes {

    private InternalTokenRoutes() {
    }

    /** 새 값이 비어 있지 않으면 그것, 아니면 옛 값. 둘 다 비면 빈 문자열(fail-closed). */
    public static String orLegacy(String preferred, String legacy) {
        if (preferred != null && !preferred.isBlank()) return preferred.trim();
        return legacy == null ? "" : legacy.trim();
    }

    public static LinkedHashMap<String, String> routes(String identity, String mail, String agents, String legacy) {
        String identityTokens = orLegacy(identity, legacy);
        String agentTokens = orLegacy(agents, legacy);
        String mailTokens = orLegacy(mail, legacy) + "," + agentTokens;

        LinkedHashMap<String, String> routes = new LinkedHashMap<>();
        routes.put("/internal/org/members/agents/**", agentTokens);
        routes.put("/internal/org/mail/**", mailTokens);
        routes.put("/internal/org/invitations/**", identityTokens);
        routes.put("/internal/org/members/ensure", identityTokens);
        routes.put("/internal/org/members/*/authz", identityTokens);
        return routes;
    }
}
