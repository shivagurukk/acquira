package com.acquira.common.repository;

import com.acquira.common.model.LoginMfaToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface LoginMfaTokenRepository extends JpaRepository<LoginMfaToken, Long> {

    /** Look up a live challenge by the opaque ticket handed to the browser. */
    Optional<LoginMfaToken> findByTicketAndUsedFalse(String ticket);

    /** One live challenge per user — prior rows are cleared when a new login starts. */
    @Modifying
    @Query("DELETE FROM LoginMfaToken t WHERE t.user.id = :userId")
    void deleteByUserId(Long userId);

    /** Housekeeping: drop challenges that can no longer be redeemed. */
    @Modifying
    @Query("DELETE FROM LoginMfaToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(LocalDateTime cutoff);

    /** Atomically spend one guess; 0 means the budget is exhausted. See PasswordResetTokenRepository. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LoginMfaToken t SET t.attemptCount = t.attemptCount + 1 "
         + "WHERE t.id = :id AND t.used = false AND t.attemptCount < :max")
    int tryConsumeAttempt(Long id, int max);
}
