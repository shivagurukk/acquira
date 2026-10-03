package com.acquira.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Keeps the JSON wire format the UI was built against after the move to
 * Spring Boot 4 / Jackson 3.
 *
 * Under Boot 3 (Jackson 2) a {@code java.sql.Date} coming out of a
 * JdbcTemplate row map was written as a plain calendar date, "2026-08-20".
 * Under Boot 4 the same value went out as a UTC instant,
 * "2026-08-19T18:30:00.000+00:00" - local midnight shifted to UTC - which
 * moves every DATE column back a day for any server east of Greenwich
 * (found by diffing every GET endpoint between the two builds). A DATE has no
 * time zone, so it is pinned to its ISO calendar form here. {@code java.sql.Time}
 * gets the same treatment for the same reason.
 *
 * Spring Boot registers every {@link JacksonModule} bean with the
 * auto-configured mapper, so this applies to all HTTP responses.
 */
@Configuration
public class JacksonCompatConfig {

    @Bean
    public JacksonModule sqlDateTimeWireFormatModule() {
        SimpleModule module = new SimpleModule("acquira-sql-date-wire-format");
        module.addSerializer(java.sql.Date.class, new ValueSerializer<java.sql.Date>() {
            @Override
            public void serialize(java.sql.Date value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(value.toString());
            }
        });
        module.addSerializer(java.sql.Time.class, new ValueSerializer<java.sql.Time>() {
            @Override
            public void serialize(java.sql.Time value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(value.toString());
            }
        });
        return module;
    }
}
