package com.platform.orgservice.repository;

import com.platform.orgservice.domain.MailSetting;
import org.springframework.data.jpa.repository.JpaRepository;

/** 행은 하나뿐이다({@code id=1}) — 조회는 언제나 {@code findById(MailSetting.SINGLETON_ID)}다. */
public interface MailSettingRepository extends JpaRepository<MailSetting, Long> {
}
