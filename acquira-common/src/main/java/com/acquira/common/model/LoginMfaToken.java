package com.acquira.common.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A pending two-factor challenge raised at login.
 *
 * Deliberately separate from {@link PasswordResetToken}: a verified reset token
 * is redeemable at /api/auth/reset-password, and a passed MFA step must never
 * confer the right to change a password. Same hardening, different lifecycle.
 *
 * The row is created only after the password has already been accepted, so it
 * proves "password holder is waiting on a second factor" — nothing more. It
 * carries no authority until {@code verify-mfa} exchanges it for a real session.
 */
@Entity
@Table(name = "login_mfa_token")
public class LoginMfaToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mfa_token_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Opaque handle given to the browser in place of a JWT while MFA is pending. */
    @Column(name = "ticket", nullable = false, unique = true)
    private String ticket;

    /** BCrypt hash of the 6-digit code. The plaintext is emailed, never stored. */
    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    /** Failed verify attempts against this challenge; burned once it hits the cap. */
    @Column(name = "attempt_count")
    private int attemptCount = 0;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used")
    private boolean used = false;

    /** Client IP that started the challenge, for audit correlation. */
    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public LoginMfaToken() {}

    public LoginMfaToken(User user, String ticket, String otpHash,
                         LocalDateTime expiresAt, String ipAddress) {
        this.user = user;
        this.ticket = ticket;
        this.otpHash = otpHash;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getTicket() { return ticket; }
    public void setTicket(String ticket) { this.ticket = ticket; }
    public String getOtpHash() { return otpHash; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }
}
