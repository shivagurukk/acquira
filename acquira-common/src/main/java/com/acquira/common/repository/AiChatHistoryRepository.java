package com.acquira.common.repository;

import com.acquira.common.model.AiChatHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiChatHistoryRepository extends JpaRepository<AiChatHistory, Long> {

    /** Most recent questions for a tenant, newest first (for the "recent" list). */
    List<AiChatHistory> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    /** Most recent questions for one user within a tenant. */
    List<AiChatHistory> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId, Pageable pageable);
}
