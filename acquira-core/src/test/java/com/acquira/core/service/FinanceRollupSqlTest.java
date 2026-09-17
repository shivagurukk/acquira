package com.acquira.core.service;

import com.acquira.common.service.FinanceRollupSql;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceRollupSqlTest {

    @Test
    @DisplayName("rebuild statement binds tenant/start/end twice (pivot leg + fee leg)")
    void statementShape() {
        String sql = FinanceRollupSql.REBUILD_INSERT;
        assertEquals(6, sql.chars().filter(c -> c == '?').count());
        assertTrue(sql.startsWith("INSERT INTO sum_daily_finance_rollup ("));
        assertTrue(sql.contains("FROM sum_daily_insight s"));
        assertTrue(sql.contains("FROM sum_daily_full s"));
        assertTrue(sql.contains("FULL OUTER JOIN"));
        assertTrue(sql.contains("ON CONFLICT (tenant_id, business_date) DO UPDATE SET"));
        // Every column the read side asks for is written.
        for (String c : FinanceRollupSql.PIVOT_COLS) assertTrue(sql.contains(c + " = EXCLUDED." + c), c);
        for (String c : FinanceRollupSql.FEE_COLS) assertTrue(sql.contains(c + " = EXCLUDED." + c), c);
    }

    @Test
    @DisplayName("rebuildRange = clean-slate DELETE, INSERT, then the ancillary re-derivation for the same window")
    void rebuildRangeDeletesThenInserts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);

        int n = FinanceRollupSql.rebuildRange(jdbc, 5L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3));

        assertEquals(3, n);
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        // DELETE + REBUILD_INSERT, then AncillarySql.applyRollupRange's four
        // statements (zero-out, DCC upsert, rental upsert, cleanup) — the
        // clean-slate wiped the ancillary columns, so they must come back.
        verify(jdbc, times(6)).update(sqlCap.capture(), any(Object[].class));
        List<String> sqls = sqlCap.getAllValues();
        assertTrue(sqls.get(0).startsWith("DELETE FROM sum_daily_finance_rollup"));
        assertEquals(FinanceRollupSql.REBUILD_INSERT, sqls.get(1));
        assertTrue(sqls.get(2).startsWith("UPDATE sum_daily_finance_rollup SET dcc_acquirer = 0"));
        assertTrue(sqls.get(3).contains("FROM fact_dcc_revenue"));
        assertTrue(sqls.get(4).contains("FROM fact_rental"));
        assertTrue(sqls.get(5).startsWith("DELETE FROM sum_daily_finance_rollup"));
    }

    @Test
    @DisplayName("rebuildDates collapses contiguous days into runs so sparse uploads don't re-aggregate the gap")
    void rebuildDatesCollapsesRuns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        // Two runs: Jan 1-3 and Jun 30 (deliberately unsorted with a duplicate).
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 2));

        FinanceRollupSql.rebuildDates(jdbc, 5L, dates);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        // 2 runs x (DELETE + INSERT + 4 ancillary re-derivation statements)
        verify(jdbc, times(12)).update(anyString(), args.capture());
        Object[] firstDelete = args.getAllValues().get(0);
        assertEquals(LocalDate.of(2026, 1, 1), firstDelete[1]);
        assertEquals(LocalDate.of(2026, 1, 3), firstDelete[2]);
        Object[] secondDelete = args.getAllValues().get(6);
        assertEquals(LocalDate.of(2026, 6, 30), secondDelete[1]);
        assertEquals(LocalDate.of(2026, 6, 30), secondDelete[2]);
    }

    @Test
    @DisplayName("guards: null tenant or inverted range is a no-op")
    void guards() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertEquals(0, FinanceRollupSql.rebuildRange(jdbc, null, LocalDate.now(), LocalDate.now()));
        assertEquals(0, FinanceRollupSql.rebuildRange(jdbc, 1L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)));
        assertEquals(0, FinanceRollupSql.rebuildDates(jdbc, 1L, List.of()));
        verifyNoInteractions(jdbc);
    }
}
