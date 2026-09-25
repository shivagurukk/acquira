package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "integration_connection")
@Data
public class IntegrationConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "db_type", nullable = false)
    private DbType dbType;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(name = "db_name", nullable = false)
    private String dbName;

    @Column(nullable = false)
    private String username;

    @Column(name = "encrypted_password", nullable = false)
    private String encryptedPassword;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 30;

    @Column(name = "max_retries")
    private Integer maxRetries = 3;

    /**
     * MSSQL only: whether the JDBC URL sets trustServerCertificate=true.
     * Default TRUE preserves historical behaviour (internal networks);
     * set FALSE to enforce certificate validation on production links.
     */
    @Column(name = "trust_server_cert")
    private Boolean trustServerCert = true;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "last_test_at")
    private LocalDateTime lastTestAt;

    @Column(name = "last_test_status")
    private String lastTestStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum DbType {
        ORACLE, POSTGRES, MSSQL
    }

    /**
     * SECURITY: host/port/dbName are validated here, at the single point where
     * they become a JDBC URL, so no caller can bypass it. Without this an
     * admin-supplied dbName can append driver properties (Postgres
     * socketFactory => RCE in this JVM, MSSQL ';'-properties, Oracle wallet
     * location) — see JdbcTargetValidator.
     *
     * @JsonIgnore: this is a computed getter on a @Data entity that the
     * Integration Hub returns directly, so Jackson would (a) leak the internal
     * JDBC URL to the browser and (b) throw for any stored row whose
     * host/db/port fails validation, turning one bad row into a 500 on every
     * list screen for the tenant.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getJdbcUrl() {
        String h = com.acquira.common.util.JdbcTargetValidator.requireValidHost(host);
        int p = com.acquira.common.util.JdbcTargetValidator.requireValidPort(port);
        String db = com.acquira.common.util.JdbcTargetValidator.requireValidDbName(dbName);
        switch (dbType) {
            case ORACLE:
                return "jdbc:oracle:thin:@" + h + ":" + p + "/" + db;
            case POSTGRES:
                return "jdbc:postgresql://" + h + ":" + p + "/" + db;
            case MSSQL:
                boolean trust = trustServerCert == null || trustServerCert;
                // packetSize=32767 (max): the default 8000-byte TDS packets add
                // measurable per-packet overhead when draining wide NVARCHAR
                // result sets (UTF-16 on the wire) over a WAN/VPN — the merchant
                // master pull streams 100+ MB. Larger packets = fewer round
                // trips through TLS + any inspecting firewall.
                return "jdbc:sqlserver://" + h + ":" + p + ";databaseName=" + db
                        + ";encrypt=true;trustServerCertificate=" + trust
                        + ";packetSize=32767";
            default:
                throw new IllegalArgumentException("Unsupported DB Type: " + dbType);
        }
    }
}
