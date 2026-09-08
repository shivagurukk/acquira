package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "data_source_config")
public class DataSourceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name; // e.g., "Core Banking Oracle"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DbType dbType; // ORACLE, POSTGRES, MSSQL

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private String port;

    @Column(nullable = false)
    private String dbName; // Service Name for Oracle

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String encryptedPassword;

    private boolean isActive = true;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;

    public enum DbType {
        ORACLE, POSTGRES, MSSQL
    }

    /** SECURITY: see IntegrationConnection.getJdbcUrl — same injection risk. */
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
                return "jdbc:sqlserver://" + h + ":" + p + ";databaseName=" + db
                        + ";encrypt=true;trustServerCertificate=true";
            default:
                throw new IllegalArgumentException("Unsupported DB Type: " + dbType);
        }
    }
}
