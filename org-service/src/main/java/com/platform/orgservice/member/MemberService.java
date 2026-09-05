package com.platform.orgservice.member;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.domain.MemberKind;
import com.platform.orgservice.domain.MemberStatus;
import com.platform.orgservice.invitation.InvitationService;
import com.platform.orgservice.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository members;
    private final InvitationService invitations;

    /**
     * 미러링 결과.
     *
     * @param blocked 이 요청을 {@code /api/org/me} 밖으로 보내면 안 되는가
     * @param reason  막을 때 사용자에게 보여줄 한국어 문구
     */
    public record MirrorResult(boolean blocked, String reason) {
        static final MirrorResult PASS = new MirrorResult(false, null);
    }

    /**
     * JIT 미러링 upsert + 초대 소진.
     *
     * <p>처음 보는 사람은 <b>PENDING으로</b> 만든다 — 초대 없이 들어온 계정이 곧바로 아무 데나
     * 들어가지 못하게 하는 것이 U1의 핵심이다. 그 직후 이메일이 일치하는 살아 있는 초대가 있으면
     * 그 자리에서 소진해 활성 사용자로 만든다(구글처럼 이메일을 신뢰할 수 있는 경로에 한해).
     *
     * <p>최초 동시 요청 2건의 PK 충돌은 무시한다(다음 요청이 refresh). kind==AGENT인 행은
     * refresh를 스킵한다 — agent-service가 등록한 페르소나 이름/이메일이 우연히 같은 id로 들어오는
     * JWT에 덮이면 안 된다.
     */
    @Transactional
    public MirrorResult mirror(long id, String displayName, String email, String provider) {
        Member member = members.findById(id).orElse(null);
        if (member == null) {
            try {
                member = members.saveAndFlush(Member.joining(id, displayName, email));
            } catch (DataIntegrityViolationException e) {
                member = members.findById(id).orElse(null);
                if (member == null) return MirrorResult.PASS; // 판단할 근거가 없으면 통과시킨다(기존 동작)
            }
        } else if (member.getKind() != MemberKind.AGENT) {
            member.refresh(displayName, email);
        }

        // 초대 소진은 "전체 구성원" 합류까지 InvitationService가 한 번에 처리한다.
        // 활성 계정에 대해 매 요청 확인하지 않는 이유는 인증 경로에 쿼리가 상시로 붙기 때문이다 —
        // 기존 계정의 백필은 기동 시 EveryoneTeamSeeder가 맡는다.
        if (member.getStatus() == MemberStatus.PENDING) {
            invitations.consumeOnLogin(member, provider);
        }
        return switch (member.getStatus()) {
            case PENDING -> new MirrorResult(true, "승인 대기 중인 계정입니다");
            case SUSPENDED -> new MirrorResult(true, "정지된 계정입니다");
            case DEACTIVATED -> new MirrorResult(true, "비활성된 계정입니다");
            case ACTIVE -> MirrorResult.PASS;
        };
    }

    /** 에이전트 멤버 등록(upsert) — 존재하면 이름/이메일 갱신 + kind=AGENT 유지, 없으면 새로 생성. */
    @Transactional
    public Member registerAgent(long id, String displayName, String email) {
        return members.findById(id)
                .map(m -> { m.refreshAsAgent(displayName, email); return m; })
                .orElseGet(() -> members.save(Member.agentOf(id, displayName, email)));
    }
}
