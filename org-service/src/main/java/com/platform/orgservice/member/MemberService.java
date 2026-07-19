package com.platform.orgservice.member;

import com.platform.orgservice.domain.Member;
import com.platform.orgservice.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository members;

    /** JIT 미러링 upsert. 최초 동시 요청 2건의 PK 충돌은 무시(다음 요청이 refresh). */
    @Transactional
    public void mirror(long id, String displayName, String email) {
        try {
            members.findById(id).ifPresentOrElse(
                    m -> m.refresh(displayName, email),
                    () -> members.save(Member.of(id, displayName, email)));
        } catch (DataIntegrityViolationException ignored) {
        }
    }
}
