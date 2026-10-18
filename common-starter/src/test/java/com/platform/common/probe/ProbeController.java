package com.platform.common.probe;

import com.platform.common.error.BadRequestException;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {
    public record Body(@NotBlank(message = "이름은 비울 수 없습니다") String name) {}

    @GetMapping("/probe/missing") public String missing() { throw new NotFoundException("없음"); }
    @GetMapping("/probe/conflict") public String conflict() { throw new ConflictException("충돌"); }
    @GetMapping("/probe/bad") public String bad() { throw new IllegalArgumentException("잘못됨"); }
    @PostMapping("/probe/valid") public String valid(@Valid @RequestBody Body body) { return body.name(); }

    // P-11 — 계약 밖으로 새던 모양들
    @GetMapping("/probe/library-iae") public String libraryIae() {
        throw new IllegalArgumentException("No enum constant com.platform.Secret.VALUE");
    }
    @GetMapping("/probe/number-format") public String numberFormat() { return String.valueOf(Long.parseLong("abc")); }
    @GetMapping("/probe/bad-request") public String badRequest() { throw new BadRequestException("Title too long"); }
    @GetMapping("/probe/items/{id}") public String item(@PathVariable("id") Long id) { return String.valueOf(id); }
    @GetMapping("/probe/param") public String param(@RequestParam("q") String q) { return q; }
    @GetMapping("/probe/locked") public String locked() {
        throw new ObjectOptimisticLockingFailureException("com.platform.Page", 1L);
    }
    @GetMapping("/probe/denied") public String denied() { throw new AccessDeniedException("Access Denied"); }
}
