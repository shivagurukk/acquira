package com.acquira.common.repository;

import com.acquira.common.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenAndUsedFalse(String token);

    // Newest still-usable OTP row for a user — used by verify-otp and resend.
    Optional<PasswordResetToken> findFirstByUser_IdAndUsedFalseOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.user.id = :userId")
    void deleteByUserId(Long userId);

    /**
     * Atomically spend one guess. Returns 1 if a slot was taken, 0 if the budget
     * was already exhausted. A read-check-save in Java let N concurrent requests
     * all see attemptCount &lt; max before any of them wrote (pentest 2026-10-04, H4).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetToken t SET t.attemptCount = t.attemptCount + 1 "
         + "WHERE t.id = :id AND t.used = false AND t.attemptCount < :max")
    int tryConsumeAttempt(Long id, int max);
}
