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

    public String getJdbcUrl() {
        switch (dbType) {
            case ORACLE:
                return "jdbc:oracle:thin:@" + host + ":" + port + "/" + dbName;
            case POSTGRES:
                return "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
            case MSSQL:
                boolean trust = trustServerCert == null || trustServerCert;
                return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + dbName
                        + ";encrypt=true;trustServerCertificate=" + trust;
            default:
                throw new IllegalArgumentException("Unsupported DB Type: " + dbType);
        }
    }
}
