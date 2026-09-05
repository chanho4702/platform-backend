package com.platform.orgservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling}은 초대 만료 배치({@code InvitationExpiryScheduler})가 돌기 위한 것이다. */
@SpringBootApplication
@EnableScheduling
public class OrgServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrgServiceApplication.class, args);
    }
}
