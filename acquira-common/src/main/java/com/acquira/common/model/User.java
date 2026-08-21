package com.acquira.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@JsonIgnoreProperties(ignoreUnknown = true)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @JsonIgnore // SECURITY FIX: Never expose password hash in API responses
    @Column(name = "password_hash", nullable = false)
    private String password;

    private String email;

    @Column(name = "role")
    private String role; // ROLE_SUPER_ADMIN, ROLE_USER, etc.

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // ===== PASSWORD MANAGEMENT =====
    @Column(name = "must_change_password")
    private boolean mustChangePassword = false;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    // ===== ACCOUNT LOCKOUT =====
    @Column(name = "failed_login_attempts")
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_failed_login")
    private LocalDateTime lastFailedLogin;

    // ===== SSO FIELDS =====
    @Column(name = "sso_provider")
    private String ssoProvider;  // "MICROSOFT", null

    @Column(name = "sso_id")
    private String ssoId;  // Azure AD Object ID

    @Column(name = "approval_status")
    private String approvalStatus = "APPROVED";  // APPROVED, PENDING, REJECTED

    @Column(name = "display_name")
    private String displayName;

    // ===== ACCOUNT EXPIRY =====
    // Optional cutoff. After this timestamp the account is expired: login is
    // blocked and the user is auto-deactivated. NULL = never expires.
    @Column(name = "account_expires_at")
    private LocalDateTime accountExpiresAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @JsonIgnore // Never return password in JSON
    public String getPassword() {
        return password;
    }

    @JsonProperty // Allow setting password from JSON (e.g. create user request)
    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role != null ? role : "ROLE_USER";
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // ===== Password Management Getters/Setters =====
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }

    // ===== Account Lockout Getters/Setters =====
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }

    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }

    public LocalDateTime getLastFailedLogin() { return lastFailedLogin; }
    public void setLastFailedLogin(LocalDateTime lastFailedLogin) { this.lastFailedLogin = lastFailedLogin; }

    public boolean isAccountLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    // ===== SSO Getters/Setters =====
    public String getSsoProvider() { return ssoProvider; }
    public void setSsoProvider(String ssoProvider) { this.ssoProvider = ssoProvider; }

    public String getSsoId() { return ssoId; }
    public void setSsoId(String ssoId) { this.ssoId = ssoId; }

    public String getApprovalStatus() { return approvalStatus != null ? approvalStatus : "APPROVED"; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public LocalDateTime getAccountExpiresAt() { return accountExpiresAt; }
    public void setAccountExpiresAt(LocalDateTime accountExpiresAt) { this.accountExpiresAt = accountExpiresAt; }

    /** True when an expiry is set and it has passed. */
    public boolean isAccountExpired() {
        return accountExpiresAt != null && accountExpiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isSsoUser() { return ssoProvider != null && !ssoProvider.isEmpty(); }
    public boolean isPendingApproval() { return "PENDING".equals(approvalStatus); }
}
