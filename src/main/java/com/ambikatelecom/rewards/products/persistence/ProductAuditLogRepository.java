package com.ambikatelecom.rewards.products.persistence;

import com.ambikatelecom.rewards.products.domain.ProductAuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductAuditLogRepository extends JpaRepository<ProductAuditLog, Long> {
    List<ProductAuditLog> findTop50ByProductIdOrderByCreatedAtDescIdDesc(Long productId);

    List<ProductAuditLog> findTop50ByEntityTypeOrderByCreatedAtDescIdDesc(String entityType);
}
