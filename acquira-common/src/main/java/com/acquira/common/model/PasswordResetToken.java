package com.acquira.common.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // After OTP verification this holds the opaque single-use RESET TICKET
    // handed back to the client, so the set-password call never resends the OTP
    // over the wire. Nullable at creation time (only the OTP hash is set then).
    @Column(name = "token", unique = true)
    private String token;

    // BCrypt hash of the 6-digit OTP. The plaintext code is emailed and never
    // persisted. Nullable so legacy link-only rows (if any) still map.
    @Column(name = "otp_hash")
    private String otpHash;

    // Failed verify-otp attempts against THIS token; locked once it hits the cap.
    @Column(name = "attempt_count")
    private int attemptCount = 0;

    // Set true once the OTP is verified; gate for the set-password step.
    @Column(name = "verified")
    private boolean verified = false;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used")
    private boolean used = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public PasswordResetToken() {}

    // Legacy link-style ctor (token known up front).
    public PasswordResetToken(User user, String token, LocalDateTime expiresAt) {
        this.user = user;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    // OTP-style ctor (only the OTP hash + expiry known at creation; the reset
    // ticket `token` is filled in later, on successful verification).
    public PasswordResetToken(User user, String otpHash, LocalDateTime expiresAt, boolean otp) {
        this.user = user;
        this.otpHash = otpHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getOtpHash() { return otpHash; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }
}
