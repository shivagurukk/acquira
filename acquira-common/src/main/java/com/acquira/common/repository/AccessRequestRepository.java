package com.acquira.common.repository;

import com.acquira.common.model.AccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {
    List<AccessRequest> findByStatus(String status);
    List<AccessRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<AccessRequest> findAllByOrderByCreatedAtDesc();
    Optional<AccessRequest> findByEmailAndStatus(String email, String status);
    boolean existsByEmailAndStatus(String email, String status);
}
