package com.platform.orgservice.member;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberKind;
import com.platform.orgservice.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository members;

    /**
     * JIT 미러링 upsert. 최초 동시 요청 2건의 PK 충돌은 무시(다음 요청이 refresh).
     * kind==AGENT인 행은 refresh를 스킵한다 — 사람 로그인 클레임이 아니라 agent-service가
     * 등록한 페르소나 이름/이메일이 우연히 같은 id로 들어오는 JWT에 덮이면 안 된다.
     */
    @Transactional
    public void mirror(long id, String displayName, String email) {
        try {
            members.findById(id).ifPresentOrElse(
                    m -> { if (m.getKind() != MemberKind.AGENT) m.refresh(displayName, email); },
                    () -> members.save(Member.of(id, displayName, email)));
        } catch (DataIntegrityViolationException ignored) {
        }
    }

    /** 에이전트 멤버 등록(upsert) — 존재하면 이름/이메일 갱신 + kind=AGENT 유지, 없으면 새로 생성. */
    @Transactional
    public Member registerAgent(long id, String displayName, String email) {
        return members.findById(id)
                .map(m -> { m.refreshAsAgent(displayName, email); return m; })
                .orElseGet(() -> members.save(Member.agentOf(id, displayName, email)));
    }
}
