package com.acquira.common.tools;

import com.acquira.common.security.SecretCrypto;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Standalone command-line tool to ENCRYPT (or verify-decrypt) a secret so the
 * resulting {@code enc:v1:...} token can be pasted into application.properties.
 *
 * It is intentionally NOT a Spring component — it has its own main() and uses
 * only the JDK + {@link SecretCrypto}, so it can be run directly from the
 * compiled classes without booting the application.
 *
 * ── Master key ──────────────────────────────────────────────────────────────
 * The key is taken from (first one wins):
 *   1. env var  APP_ENCRYPTION_KEY
 *   2. system prop -Dapp.encryption.key=...
 *   3. interactive prompt (hidden input)
 * It MUST be the same key the app uses (app.encryption.key / APP_ENCRYPTION_KEY)
 * or the app won't be able to decrypt the token. Minimum 32 characters.
 *
 * ── Usage ───────────────────────────────────────────────────────────────────
 * Compile step produces classes under acquira-common/target/classes, then:
 *
 *   # encrypt (value passed as arg)
 *   set APP_ENCRYPTION_KEY=my-32+char-master-key................
 *   java -cp acquira-common/target/classes com.acquira.common.tools.SecretEncryptorTool encrypt "MyDbP@ssw0rd"
 *
 *   # encrypt (value typed at hidden prompt — keeps it out of shell history)
 *   java -cp acquira-common/target/classes com.acquira.common.tools.SecretEncryptorTool encrypt
 *
 *   # verify a token round-trips
 *   java -cp acquira-common/target/classes com.acquira.common.tools.SecretEncryptorTool decrypt "enc:v1:AAAA...."
 *
 * The printed token (enc:v1:....) goes verbatim into the property value, e.g.
 *   spring.datasource.password=enc:v1:AAAA....
 * and the app must run with acquira.secrets.provider=ENCRYPTED.
 */
public final class SecretEncryptorTool {

    private SecretEncryptorTool() { }

    public static void main(String[] args) {
        try {
            String mode = (args.length > 0) ? args[0].trim().toLowerCase() : "encrypt";
            if (!mode.equals("encrypt") && !mode.equals("decrypt")) {
                // Allow shorthand: first arg is the plaintext, default to encrypt.
                mode = "encrypt";
            }

            String key = resolveKey();
            if (key == null || key.isBlank()) {
                System.err.println("ERROR: no master key. Set APP_ENCRYPTION_KEY env var "
                        + "or -Dapp.encryption.key=..., at least 32 characters.");
                System.exit(2);
                return;
            }

            String input = readInput(args, mode);
            if (input == null || input.isEmpty()) {
                System.err.println("ERROR: nothing to " + mode + ". Provide the value as an argument or at the prompt.");
                System.exit(2);
                return;
            }

            if (mode.equals("decrypt")) {
                String plain = SecretCrypto.decrypt(input, key);
                System.out.println(plain);
            } else {
                String token = SecretCrypto.encrypt(input, key);
                // Print ONLY the token on stdout so it can be piped/copied cleanly.
                System.out.println(token);
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String resolveKey() {
        String key = System.getenv("APP_ENCRYPTION_KEY");
        if (key == null || key.isBlank()) key = System.getProperty("app.encryption.key");
        if (key == null || key.isBlank()) {
            Console console = System.console();
            if (console != null) {
                char[] entered = console.readPassword("Master key (APP_ENCRYPTION_KEY): ");
                if (entered != null) key = new String(entered);
            }
        }
        return key;
    }

    /**
     * Pull the value to process. If passed as the 2nd CLI arg (or 1st when the
     * mode keyword is omitted) use it; otherwise read a single line from stdin,
     * preferring a hidden console prompt so passwords don't echo or hit history.
     */
    private static String readInput(String[] args, String mode) throws Exception {
        boolean firstArgIsMode = args.length > 0
                && (args[0].equalsIgnoreCase("encrypt") || args[0].equalsIgnoreCase("decrypt"));
        int valueIdx = firstArgIsMode ? 1 : 0;
        if (args.length > valueIdx) {
            return args[valueIdx];
        }
        Console console = System.console();
        if (console != null && mode.equals("encrypt")) {
            char[] entered = console.readPassword("Plaintext to encrypt: ");
            return entered == null ? null : new String(entered);
        }
        if (console != null) {
            String line = console.readLine("Token to decrypt: ");
            return line;
        }
        // No console (piped input) — read one line from stdin.
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            return br.readLine();
        }
    }
}
