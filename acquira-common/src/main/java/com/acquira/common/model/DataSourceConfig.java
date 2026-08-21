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

    public String getJdbcUrl() {
        switch (dbType) {
            case ORACLE:
                return "jdbc:oracle:thin:@" + host + ":" + port + "/" + dbName;
            case POSTGRES:
                return "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
            case MSSQL:
                return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + dbName
                        + ";encrypt=true;trustServerCertificate=true";
            default:
                throw new IllegalArgumentException("Unsupported DB Type: " + dbType);
        }
    }
}
