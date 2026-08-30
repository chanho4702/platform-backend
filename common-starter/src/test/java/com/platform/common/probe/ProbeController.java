package com.platform.common.probe;

import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {
    public record Body(@NotBlank(message = "이름은 비울 수 없습니다") String name) {}

    @GetMapping("/probe/missing") public String missing() { throw new NotFoundException("없음"); }
    @GetMapping("/probe/conflict") public String conflict() { throw new ConflictException("충돌"); }
    @GetMapping("/probe/bad") public String bad() { throw new IllegalArgumentException("잘못됨"); }
    @PostMapping("/probe/valid") public String valid(@Valid @RequestBody Body body) { return body.name(); }
}
