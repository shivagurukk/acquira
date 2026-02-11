package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_combined_view", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "view_name" })
})
@Data
public class UserCombinedView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long viewId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "view_name", nullable = false)
    private String viewName; // e.g., "MENA Region", "My Portfolio"

    @Column(name = "tenant_ids", nullable = false)
    private String tenantIds; // Comma-separated list: "1,2,5"

    @Column(name = "is_default")
    private boolean isDefault = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
