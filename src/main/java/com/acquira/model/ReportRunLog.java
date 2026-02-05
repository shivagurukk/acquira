package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "report_run_log")
public class ReportRunLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "query_id")
    private ReportQueryConfig query;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Integer rowCount;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public enum Status {
        RUNNING, SUCCESS, FAILED
    }
}
