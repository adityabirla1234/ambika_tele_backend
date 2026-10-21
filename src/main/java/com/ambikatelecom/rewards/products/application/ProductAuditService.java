package com.ambikatelecom.rewards.products.application;

import com.ambikatelecom.rewards.products.domain.ProductAuditLog;
import com.ambikatelecom.rewards.products.persistence.ProductAuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProductAuditService {

    private final ProductAuditLogRepository repo;
    private final ObjectMapper mapper;

    public ProductAuditService(ProductAuditLogRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** Joins the caller's transaction, so the audit row commits or rolls back with the change itself. */
    public void record(String entityType, Long entityId, Long productId, String action, String actor,
                       Map<String, Object> changes) {
        String json;
        try {
            json = mapper.writeValueAsString(changes);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        repo.save(new ProductAuditLog(entityType, entityId, productId, action, actor, json));
    }
}
