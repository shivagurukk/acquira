package com.acquira.core.service;

import com.acquira.common.model.PasswordHistory;
import com.acquira.common.model.User;
import com.acquira.common.repository.PasswordHistoryRepository;
import com.acquira.common.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PasswordService {

    private final PasswordHistoryRepository historyRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    // Defaults — can be overridden from tenant_setting
    private int historyCount = 5;
    private int minLength = 8;

    public PasswordService(PasswordHistoryRepository historyRepository,
                           PasswordEncoder passwordEncoder,
                           UserRepository userRepository) {
        this.historyRepository = historyRepository;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
    }

    /**
     * Validate password strength. Returns null if valid, error message otherwise.
     */
    public String validatePasswordStrength(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < minLength) {
            return "Password must be at least " + minLength + " characters";
        }
        if (!rawPassword.matches(".*[A-Z].*")) {
            return "Password must contain at least one uppercase letter";
        }
        if (!rawPassword.matches(".*[a-z].*")) {
            return "Password must contain at least one lowercase letter";
        }
        if (!rawPassword.matches(".*[0-9].*")) {
            return "Password must contain at least one digit";
        }
        if (!rawPassword.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            return "Password must contain at least one special character (!@#$%^&*...)";
        }
        return null; // Valid
    }

    /**
     * Check if password was used in the last N passwords.
     * Returns true if REUSED (bad), false if new (good).
     */
    public boolean isPasswordReused(User user, String rawPassword) {
        List<PasswordHistory> history = historyRepository.findByUserOrderByCreatedAtDesc(user);
        int limit = Math.min(historyCount, history.size());
        for (int i = 0; i < limit; i++) {
            if (passwordEncoder.matches(rawPassword, history.get(i).getPasswordHash())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Record password in history after successful change.
     */
    @Transactional
    public void recordPasswordInHistory(User user, String encodedPassword) {
        historyRepository.save(new PasswordHistory(user, encodedPassword));

        // Cleanup: keep only last N entries
        List<PasswordHistory> history = historyRepository.findByUserOrderByCreatedAtDesc(user);
        if (history.size() > historyCount) {
            List<Long> keepIds = history.subList(0, historyCount)
                    .stream().map(PasswordHistory::getId).toList();
            historyRepository.deleteByUserAndIdNotIn(user, keepIds);
        }
    }

    /**
     * User self-service: change own password.
     * Validates current password, strength, and history.
     * Returns null on success, error message on failure.
     */
    @Transactional
    public String changePassword(User user, String currentPassword, String newPassword) {
        // 1. Verify current password
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return "Current password is incorrect";
        }

        // 2. Validate strength
        String strengthError = validatePasswordStrength(newPassword);
        if (strengthError != null) return strengthError;

        // 3. Check not same as current
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            return "New password must be different from current password";
        }

        // 4. Check history
        if (isPasswordReused(user, newPassword)) {
            return "Cannot reuse any of your last " + historyCount + " passwords";
        }

        // 5. Encode and save
        String encoded = passwordEncoder.encode(newPassword);
        user.setPassword(encoded);
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);

        // 6. Record in history
        recordPasswordInHistory(user, encoded);

        return null; // Success
    }

    /**
     * Admin reset: set password for another user.
     * NO current-password check required (admin privilege).
     * Validates strength and history.
     * Sets must_change_password = true so user changes on next login.
     * Returns null on success, error message on failure.
     */
    @Transactional
    public String adminResetPassword(User user, String newPassword) {
        // 1. Validate strength
        String strengthError = validatePasswordStrength(newPassword);
        if (strengthError != null) return strengthError;

        // 2. Check history
        if (isPasswordReused(user, newPassword)) {
            return "Cannot reuse any of the user's last " + historyCount + " passwords";
        }

        // 3. Encode and save
        String encoded = passwordEncoder.encode(newPassword);
        user.setPassword(encoded);
        user.setMustChangePassword(true); // Force change on next login
        user.setPasswordChangedAt(LocalDateTime.now());

        // Also clear any lockout when admin resets password
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLogin(null);

        userRepository.save(user);

        // 4. Record in history
        recordPasswordInHistory(user, encoded);

        return null;
    }

    /**
     * Encode password for new user creation and record in history.
     */
    @Transactional
    public String encodeAndRecordInitialPassword(User user, String rawPassword) {
        String encoded = passwordEncoder.encode(rawPassword);
        user.setPassword(encoded);
        user.setMustChangePassword(true);
        User saved = userRepository.save(user);
        recordPasswordInHistory(saved, encoded);
        return encoded;
    }

    public int getHistoryCount() { return historyCount; }
    public void setHistoryCount(int historyCount) { this.historyCount = historyCount; }
    public int getMinLength() { return minLength; }
    public void setMinLength(int minLength) { this.minLength = minLength; }
}
