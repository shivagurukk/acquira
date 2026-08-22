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
     */
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
                return "jdbc:sqlserver://" + h + ":" + p + ";databaseName=" + db
                        + ";encrypt=true;trustServerCertificate=" + trust;
            default:
                throw new IllegalArgumentException("Unsupported DB Type: " + dbType);
        }
    }
}
