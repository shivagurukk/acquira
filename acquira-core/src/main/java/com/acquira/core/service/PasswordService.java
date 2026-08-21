package com.acquira.core.service;

import com.acquira.common.model.PasswordHistory;
import com.acquira.common.model.User;
import com.acquira.common.repository.PasswordHistoryRepository;
import com.acquira.common.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PasswordService {

    private final PasswordHistoryRepository historyRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final SecurityPolicyService policyService;

    // Legacy defaults — retained only as a fallback for getters. Live rules now
    // come from SecurityPolicyService (Admin > Security Settings).
    private int historyCount = 5;
    private int minLength = 8;

    /**
     * Base words attackers try first (plus brand/app terms). Compared by exact
     * match against the password's letters / alphanumerics / leading letters, so
     * "password", "Password1!", "Welcome@123", "Acquira2024" are all rejected,
     * while genuine passphrases that merely contain a short word are not.
     * For production, back this with a breach corpus (e.g. the HaveIBeenPwned
     * k-anonymity range API, or a bundled top-100k common-password list).
     * This list is consulted only when the "block breached/common passwords"
     * policy toggle is enabled (default on).
     */
    private static final java.util.Set<String> WEAK_BASES = java.util.Set.of(
        "password","passwords","passw0rd","pass","admin","administrator","root","welcome",
        "login","qwerty","qwertyuiop","asdf","asdfgh","zxcvbn","letmein","changeme","secret",
        "iloveyou","monkey","dragon","master","superman","sunshine","football","baseball",
        "abc","abcd","abc123","123","1234","12345","123456","1234567","12345678","123456789",
        "111111","000000","654321","trustno1","whatever","access","shadow","ninja","hello",
        "acquira","bank","banking","merchant","finance","test","demo","user","guest","temp","welcome123"
    );

    private static final java.util.List<String> KEYBOARD_RUNS = java.util.List.of(
        "qwertyuiop","asdfghjkl","zxcvbnm","1234567890"
    );

    public PasswordService(PasswordHistoryRepository historyRepository,
                           PasswordEncoder passwordEncoder,
                           UserRepository userRepository,
                           SecurityPolicyService policyService) {
        this.historyRepository = historyRepository;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.policyService = policyService;
    }

    /**
     * Validate password strength against the active policy.
     * Returns null if valid, error message otherwise.
     */
    public String validatePasswordStrength(String rawPassword) {
        return validateComposition(rawPassword, policyService.passwordPolicy(null));
    }

    /**
     * Strength check that ALSO rejects passwords derived from the user's own
     * identity (username, email, display name) when the policy requires it.
     * Prefer this overload wherever the target user is known.
     */
    public String validatePasswordStrength(String rawPassword, User user) {
        SecurityPolicyService.PasswordPolicy p = policyService.passwordPolicy(null);
        String base = validateComposition(rawPassword, p);
        if (base != null) return base;
        if (user != null && p.blockUserInfo) {
            String idReason = identifierWeakness(rawPassword, user);
            if (idReason != null) return idReason;
        }
        return null;
    }

    /** Apply composition + (optional) common-password rules from the resolved policy. */
    private String validateComposition(String rawPassword, SecurityPolicyService.PasswordPolicy p) {
        if (rawPassword == null || rawPassword.length() < p.minLength) {
            return "Password must be at least " + p.minLength + " characters";
        }
        if (p.requireUppercase && !rawPassword.matches(".*[A-Z].*")) {
            return "Password must contain at least one uppercase letter";
        }
        if (p.requireLowercase && !rawPassword.matches(".*[a-z].*")) {
            return "Password must contain at least one lowercase letter";
        }
        if (p.requireDigit && !rawPassword.matches(".*[0-9].*")) {
            return "Password must contain at least one digit";
        }
        if (p.requireSpecialChar && !rawPassword.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            return "Password must contain at least one special character (!@#$%^&*...)";
        }
        // Reject the passwords attackers try first: common words, keyboard runs,
        // repeats, all-digit — only when the policy enables it (default on).
        if (p.blockBreached) {
            String pattern = patternWeakness(rawPassword);
            if (pattern != null) return pattern;
        }
        return null; // Valid
    }

    /** Common-password / weak-pattern detection. Returns a reason, or null if OK. */
    private String patternWeakness(String rawPassword) {
        String norm = rawPassword.toLowerCase();
        String core = norm.replaceAll("[^a-z0-9]", "");       // letters + digits
        String letters = norm.replaceAll("[^a-z]", "");        // letters only
        String leadLetters = norm.replaceAll("[^a-z]+$", "");  // strip trailing digits/symbols

        for (String w : WEAK_BASES) {
            if (core.equals(w) || letters.equals(w) || leadLetters.equals(w)) {
                return "That password is too common or easily guessed — pick something more unique";
            }
        }
        for (String run : KEYBOARD_RUNS) {
            for (int i = 0; i + 4 <= run.length(); i++) {
                String seq = run.substring(i, i + 4);
                String rev = new StringBuilder(seq).reverse().toString();
                if (norm.contains(seq) || norm.contains(rev)) {
                    return "Avoid keyboard sequences like '" + seq + "'";
                }
            }
        }
        if (rawPassword.matches(".*(.)\\1{3,}.*")) {
            return "Avoid repeating the same character 4 or more times";
        }
        if (norm.matches("[0-9]+")) {
            return "Password cannot be all numbers";
        }
        return null;
    }

    /** Reject passwords that embed the user's username, email local-part, or name. */
    private String identifierWeakness(String rawPassword, User user) {
        String norm = rawPassword.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (norm.isEmpty()) return null;
        List<String> ids = new ArrayList<>();
        if (user.getUsername() != null) ids.add(user.getUsername());
        if (user.getEmail() != null && user.getEmail().contains("@")) ids.add(user.getEmail().split("@")[0]);
        if (user.getDisplayName() != null) {
            for (String part : user.getDisplayName().split("\\s+")) ids.add(part);
        }
        for (String id : ids) {
            String c = id.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (c.length() >= 3 && (norm.contains(c) || c.contains(norm))) {
                return "Password must not contain your username, name, or email";
            }
        }
        return null;
    }

    /** Effective password-history depth from the active policy (fallback to legacy field). */
    private int effectiveHistoryCount() {
        try { return policyService.passwordPolicy(null).historyCount; }
        catch (Exception e) { return historyCount; }
    }

    /**
     * Check if password was used in the last N passwords.
     * Returns true if REUSED (bad), false if new (good).
     */
    public boolean isPasswordReused(User user, String rawPassword) {
        int depth = effectiveHistoryCount();
        if (depth <= 0) return false;
        List<PasswordHistory> history = historyRepository.findByUserOrderByCreatedAtDesc(user);
        int limit = Math.min(depth, history.size());
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

        // Cleanup: keep only last N entries (per active policy)
        int depth = effectiveHistoryCount();
        List<PasswordHistory> history = historyRepository.findByUserOrderByCreatedAtDesc(user);
        if (depth > 0 && history.size() > depth) {
            List<Long> keepIds = history.subList(0, depth)
                    .stream().map(PasswordHistory::getId).toList();
            historyRepository.deleteByUserAndIdNotIn(user, keepIds);
        }
    }

    /**
     * User self-service: change own password.
     * Validates current password, minimum age, strength, and history.
     * Returns null on success, error message on failure.
     */
    @Transactional
    public String changePassword(User user, String currentPassword, String newPassword) {
        // 1. Verify current password
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return "Current password is incorrect";
        }

        // 1b. Minimum password age — stops rapid cycling to defeat history rules.
        //     Skipped when never set, age=0, or no recorded change timestamp.
        int minAgeHours = policyService.passwordPolicy(null).minPasswordAgeHours;
        if (minAgeHours > 0 && user.getPasswordChangedAt() != null
                && LocalDateTime.now().isBefore(user.getPasswordChangedAt().plusHours(minAgeHours))) {
            return "Password was changed too recently. Please wait at least "
                    + minAgeHours + " hour(s) between changes.";
        }

        // 2. Validate strength (also rejects identity-based passwords)
        String strengthError = validatePasswordStrength(newPassword, user);
        if (strengthError != null) return strengthError;

        // 3. Check not same as current
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            return "New password must be different from current password";
        }

        // 4. Check history
        if (isPasswordReused(user, newPassword)) {
            return "Cannot reuse any of your last " + effectiveHistoryCount() + " passwords";
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
     * NO current-password check required (admin privilege), and minimum-age is
     * intentionally NOT enforced — an admin must always be able to reset.
     * Validates strength and history.
     * Sets must_change_password = true so user changes on next login.
     * Returns null on success, error message on failure.
     */
    @Transactional
    public String adminResetPassword(User user, String newPassword) {
        // 1. Validate strength (also rejects identity-based passwords)
        String strengthError = validatePasswordStrength(newPassword, user);
        if (strengthError != null) return strengthError;

        // 2. Check history
        if (isPasswordReused(user, newPassword)) {
            return "Cannot reuse any of the user's last " + effectiveHistoryCount() + " passwords";
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

    public int getHistoryCount() { return effectiveHistoryCount(); }
    public void setHistoryCount(int historyCount) { this.historyCount = historyCount; }
    public int getMinLength() {
        try { return policyService.passwordPolicy(null).minLength; }
        catch (Exception e) { return minLength; }
    }
    public void setMinLength(int minLength) { this.minLength = minLength; }
}
