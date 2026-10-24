package com.platform.orgservice.repository;

import com.platform.orgservice.domain.CreationPolicy;
import com.platform.orgservice.domain.ResourceKind;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreationPolicyRepository extends JpaRepository<CreationPolicy, ResourceKind> {
}
