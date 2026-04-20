package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;

@Entity
@Table(name = "dim_terminal", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "internal_id" })
})
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Terminal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "terminal_id")
    @EqualsAndHashCode.Include
    private Long terminalId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "internal_id")
    private String internalId;

    @Column(name = "store_id")
    private Long storeId;

    private String tid;

    @Column(name = "device_number")
    private String deviceNumber;

    private String type;
    private String status;

    @Column(name = "created_date")
    private java.time.LocalDateTime createdDate;
}
