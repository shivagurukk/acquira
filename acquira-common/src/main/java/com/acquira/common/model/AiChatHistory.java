package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Audit + history row for every AI Assistant question. Written once per
 * /api/ai/ask call (success or failure) by AiQueryService. Powers the
 * "recent questions" list and gives AI-executed SQL an audit trail, matching
 * the audit discipline every other sensitive action follows.
 *
 * DDL: acquira-core/src/main/resources/schema.sql (ai_chat_history).
 */
@Entity
@Table(name = "ai_chat_history")
@Data
public class AiChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    // FK -> users(user_id), NOT NULL in schema.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "question", columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(name = "generated_sql", columnDefinition = "TEXT")
    private String generatedSql;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "is_error")
    private Boolean isError = Boolean.FALSE;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getChatId() { return chatId; }
    public void setChatId(Long chatId) { this.chatId = chatId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getGeneratedSql() { return generatedSql; }
    public void setGeneratedSql(String generatedSql) { this.generatedSql = generatedSql; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Boolean getIsError() { return isError; }
    public void setIsError(Boolean isError) { this.isError = isError; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
