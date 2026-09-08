package com.acquira.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * ML churn-risk scorer (Phase 2 model: Smile RandomForest).
 *
 * Predicts the probability that a merchant goes DORMANT in the next ~30-60 days,
 * trained on the tenant's OWN history:
 *
 *   Labels  (free, from merchant_activity_summary):
 *       features observed at a past anchor date A; label = 1 if the merchant's
 *       activity snapshot at/just-after A + HORIZON is DORMANT. Training samples
 *       MULTIPLE anchors stepped across the whole available snapshot history
 *       (multi-anchor), so the weekly retrain learns from all labeled data,
 *       not a single 45-day window.
 *   Features (from sum_daily_merchant, settlement currency = total_base_volume):
 *       f0  recent-vs-baseline volume trend        (7d avg / 28d avg, clamped)
 *       f1  volume volatility                       (stddev/mean of daily volume)
 *       f2  days since last transaction             (recency, normalised)
 *       f3  month-over-month volume decline         (0..1)
 *       f4  average-ticket drift                    (recent ticket / baseline ticket)
 *       f5  transaction-count trend                 (7d cnt / 28d cnt)
 *
 * Model ladder (best available wins, lower rungs are automatic fallbacks):
 *   1. Smile RandomForest (non-linear, robust to feature scaling/interactions)
 *   2. pure-Java L2 logistic regression (if Smile fit fails for any reason)
 *   3. deterministic heuristic (cold start / too little labeled data)
 *
 * Persistence: /opt/acquira/models/{tenantId}/churn.model — RandomForest via Java
 * serialization (Smile 2.x models are Serializable). The loader SNIFFS the format:
 * binary deserialization first, then legacy CSV logistic weights (backward compat
 * with files written by the v1 scorer), else null → retrain/heuristic. A stale or
 * unreadable file can therefore never crash anything; worst case it retrains.
 *
 * Design guarantees (runs inside the ingestion batch — must NEVER break it):
 *   - The batch step wraps calls in try/catch AND this class swallows its own errors,
 *     including Throwable around all Smile calls (so even linkage errors degrade to
 *     the logistic/heuristic rungs instead of failing the job).
 *   - Scoring stays per-upload and cheap; TRAINING runs only in the weekly
 *     Saturday-9PM scheduler (ChurnRetrainScheduler) or on true cold start.
 */
@Service
@Slf4j
public class ChurnScoringService {

    private final JdbcTemplate jdbc;
    private final String modelDir;

    private static final int N_FEATURES = 6;
    private static final String[] FEATURE_NAMES = {"f0", "f1", "f2", "f3", "f4", "f5"};
    private static final int RECENT_DAYS = 7;
    private static final int BASELINE_DAYS = 28;
    private static final int HORIZON_DAYS = 45;      // "will churn within ~45 days"
    private static final int LOOKBACK_DAYS = 120;    // feature observation window
    private static final int MIN_POSITIVES = 20;     // need this many churn examples to train
    private static final int MIN_ROWS      = 100;    // and this many total examples
    // Multi-anchor training: anchors stepped back 7 days apart, capped. Labels come
    // from merchant_activity_summary snapshots (retained ~90 days), so anchors only
    // produce labels while a snapshot exists near anchor+HORIZON.
    private static final int ANCHOR_STEP_DAYS = 7;
    private static final int MAX_ANCHORS = 8;
    private static final int LABEL_MATCH_WINDOW_DAYS = 14; // snapshot must exist within this of the target
    private static final String MODEL_VERSION_RF = "churn-rf-v2";
    private static final String MODEL_VERSION_LR = "churn-logreg-v1";

    public ChurnScoringService(JdbcTemplate jdbc,
                               @Value("${acquira.ml.model-dir:/opt/acquira/models}") String modelDir) {
        this.jdbc = jdbc;
        this.modelDir = modelDir;
    }

    // ── Public API (unchanged signatures) ──────────────────────────────────

    /**
     * Score every merchant with recent activity for a tenant, upserting one row per
     * merchant into merchant_churn_score at asOf. Loads the persisted model if
     * present; trains once only on true cold start (no model file yet). Best-effort:
     * returns the number of merchants scored, 0 on any failure.
     */
    public int trainAndScore(Long tenantId) {
        if (tenantId == null) return 0;
        try {
            LocalDate asOf = maxBusinessDate(tenantId);
            if (asOf == null) {
                log.info("Churn: no summary data for tenant {} — skipping", tenantId);
                return 0;
            }

            Object model = loadModel(tenantId);
            if (model == null) {
                model = train(tenantId, asOf);        // cold start: train once
                if (model != null) saveModel(tenantId, model);
            }
            return scoreAll(tenantId, asOf, model);
        } catch (Exception e) {
            log.warn("Churn scoring failed for tenant {} (non-fatal): {}", tenantId, e.toString());
            return 0;
        }
    }

    /** Force a retrain (weekly scheduler). Returns true if a model was produced. */
    public boolean retrain(Long tenantId) {
        if (tenantId == null) return false;
        try {
            LocalDate asOf = maxBusinessDate(tenantId);
            if (asOf == null) return false;
            Object model = train(tenantId, asOf);
            if (model != null) { saveModel(tenantId, model); return true; }
            return false;
        } catch (Exception e) {
            log.warn("Churn retrain failed for tenant {} (non-fatal): {}", tenantId, e.toString());
            return false;
        }
    }

    // ── Feature extraction (unchanged) ─────────────────────────────────────

    /**
     * Pull per-merchant features as of a given date from sum_daily_merchant.
     * Returns merchantId → double[N_FEATURES]. Only merchants with any row in
     * the lookback window are included.
     */
    private Map<Long, double[]> extractFeatures(Long tenantId, LocalDate asOf) {
        final LocalDate lookbackStart = asOf.minusDays(LOOKBACK_DAYS - 1);
        final LocalDate recentStart   = asOf.minusDays(RECENT_DAYS - 1);
        final LocalDate baseEnd       = asOf.minusDays(RECENT_DAYS);
        final LocalDate baseStart     = asOf.minusDays(RECENT_DAYS + BASELINE_DAYS - 1);
        final LocalDate prevMonEnd    = asOf.minusDays(30);
        final LocalDate prevMonStart  = asOf.minusDays(59);

        // One scan; aggregate windows via CASE. Volume = total_base_volume (settlement).
        // NOTE: rows after asOf are excluded so anchor-date features never peek at the future.
        String sql =
            "SELECT s.merchant_id, " +
            "  SUM(CASE WHEN s.business_date >= ? THEN COALESCE(s.total_base_volume,0) ELSE 0 END) AS r_vol, " +
            "  SUM(CASE WHEN s.business_date >= ? THEN COALESCE(s.total_txns,0)       ELSE 0 END) AS r_txns, " +
            "  SUM(CASE WHEN s.business_date BETWEEN ? AND ? THEN COALESCE(s.total_base_volume,0) ELSE 0 END) AS b_vol, " +
            "  SUM(CASE WHEN s.business_date BETWEEN ? AND ? THEN COALESCE(s.total_txns,0)       ELSE 0 END) AS b_txns, " +
            "  SUM(CASE WHEN s.business_date BETWEEN ? AND ? THEN COALESCE(s.total_base_volume,0) ELSE 0 END) AS pm_vol, " +
            "  SUM(CASE WHEN s.business_date >= ? THEN COALESCE(s.total_base_volume,0) ELSE 0 END) AS cm_vol, " +
            "  MAX(CASE WHEN COALESCE(s.total_txns,0) > 0 THEN s.business_date END) AS last_active, " +
            // FILTERed on total_txns > 0: ancillary-only rows (rental/DCC on
            // a no-sale day, all txn measures 0) would depress the mean and
            // inflate the volatility feature.
            "  STDDEV_POP(COALESCE(s.total_base_volume,0)) FILTER (WHERE COALESCE(s.total_txns,0) > 0) AS vol_sd, " +
            "  AVG(COALESCE(s.total_base_volume,0)) FILTER (WHERE COALESCE(s.total_txns,0) > 0) AS vol_mean " +
            "FROM sum_daily_merchant s " +
            "WHERE s.tenant_id = ? AND s.merchant_id IS NOT NULL " +
            // total_txns > 0 also gates membership: a merchant whose only rows
            // in the window are ancillary charges is not a churn candidate.
            "  AND COALESCE(s.total_txns,0) > 0 " +
            "  AND s.business_date >= ? AND s.business_date <= ? " +
            "GROUP BY s.merchant_id";

        List<Map<String, Object>> rows = jdbc.queryForList(sql,
            java.sql.Date.valueOf(recentStart),                                    // r_vol
            java.sql.Date.valueOf(recentStart),                                    // r_txns
            java.sql.Date.valueOf(baseStart), java.sql.Date.valueOf(baseEnd),      // b_vol
            java.sql.Date.valueOf(baseStart), java.sql.Date.valueOf(baseEnd),      // b_txns
            java.sql.Date.valueOf(prevMonStart), java.sql.Date.valueOf(prevMonEnd),// pm_vol
            java.sql.Date.valueOf(prevMonEnd.plusDays(1)),                         // cm_vol (last 30d)
            tenantId, java.sql.Date.valueOf(lookbackStart), java.sql.Date.valueOf(asOf));

        Map<Long, double[]> out = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> r : rows) {
            long mId = ((Number) r.get("merchant_id")).longValue();
            double rVol = num(r.get("r_vol")), rTxns = num(r.get("r_txns"));
            double bVol = num(r.get("b_vol")), bTxns = num(r.get("b_txns"));
            double pmVol = num(r.get("pm_vol")), cmVol = num(r.get("cm_vol"));
            double sd = num(r.get("vol_sd")), mean = num(r.get("vol_mean"));

            double recentDaily = rVol / RECENT_DAYS;
            double baseDaily   = bVol / BASELINE_DAYS;

            double f0 = clamp(baseDaily > 0 ? recentDaily / baseDaily : (recentDaily > 0 ? 2.0 : 0.0), 0, 3) / 3.0;
            double f1 = clamp(mean > 0 ? sd / mean : 0, 0, 3) / 3.0;
            double daysSinceLast = daysSince(r.get("last_active"), asOf);
            double f2 = clamp(daysSinceLast, 0, 60) / 60.0;
            double f3 = pmVol > 0 ? clamp((pmVol - cmVol) / pmVol, 0, 1) : 0.0;
            double recentTicket = rTxns > 0 ? rVol / rTxns : 0;
            double baseTicket   = bTxns > 0 ? bVol / bTxns : 0;
            double f4 = baseTicket > 0 ? clamp(recentTicket / baseTicket, 0, 3) / 3.0 : 0.5;
            double f5 = bTxns > 0 ? clamp((rTxns / RECENT_DAYS) / (bTxns / BASELINE_DAYS), 0, 3) / 3.0 : (rTxns > 0 ? 0.66 : 0.0);

            out.put(mId, new double[]{ f0, f1, f2, f3, f4, f5 });
        }
        return out;
    }

    // ── Training (multi-anchor over the whole labeled history) ─────────────

    /**
     * Build a labeled dataset from ALL available history and fit the best model.
     * Anchors: asOf-45, asOf-52, ... (7-day steps, up to MAX_ANCHORS). For anchor A,
     * features are observed at A (no future peeking — extractFeatures caps at A) and
     * the label is the merchant's DORMANT-ness at the snapshot nearest at/after
     * A + HORIZON. Returns a model object (RandomForest or double[] logistic
     * weights) or null when there isn't enough signal.
     */
    private Object train(Long tenantId, LocalDate asOf) {
        List<double[]> X = new ArrayList<>();
        List<Integer> y = new ArrayList<>();
        int positives = 0, anchorsUsed = 0;

        for (int a = 0; a < MAX_ANCHORS; a++) {
            LocalDate anchor = asOf.minusDays(HORIZON_DAYS + (long) a * ANCHOR_STEP_DAYS);
            LocalDate target = anchor.plusDays(HORIZON_DAYS);

            Map<Long, Integer> labels = labelsNear(tenantId, target);
            if (labels.isEmpty()) continue;           // no snapshots near this target

            Map<Long, double[]> feats = extractFeatures(tenantId, anchor);
            if (feats.isEmpty()) continue;

            int joined = 0;
            for (Map.Entry<Long, double[]> e : feats.entrySet()) {
                Integer lab = labels.get(e.getKey());
                if (lab == null) continue;
                X.add(e.getValue()); y.add(lab);
                if (lab == 1) positives++;
                joined++;
            }
            if (joined > 0) anchorsUsed++;
        }

        if (X.size() < MIN_ROWS || positives < MIN_POSITIVES) {
            log.info("Churn: tenant {} training set too weak (rows={}, positives={}, anchors={}) — heuristic fallback",
                tenantId, X.size(), positives, anchorsUsed);
            return null;
        }

        // Class-imbalance handling: oversample positives up to ~1:3 (cap x10).
        int negatives = X.size() - positives;
        if (positives > 0 && negatives > positives * 3) {
            int rep = Math.min(10, Math.max(1, negatives / (3 * positives)) );
            if (rep > 1) {
                List<double[]> extraX = new ArrayList<>();
                List<Integer> extraY = new ArrayList<>();
                for (int i = 0; i < y.size(); i++) {
                    if (y.get(i) == 1) {
                        for (int k = 1; k < rep; k++) { extraX.add(X.get(i)); extraY.add(1); }
                    }
                }
                X.addAll(extraX); y.addAll(extraY);
                log.info("Churn: tenant {} oversampled positives x{} (pos={}, neg={})", tenantId, rep, positives, negatives);
            }
        }

        // Rung 1: Smile RandomForest. Rung 2: pure-Java logistic.
        Object rf = smileFit(X, y);
        if (rf != null) {
            log.info("Churn: trained RandomForest for tenant {} on {} rows ({} positives, {} anchors)",
                tenantId, X.size(), positives, anchorsUsed);
            return rf;
        }
        double[] w = fitLogistic(X, y);
        log.info("Churn: RandomForest unavailable — trained logistic for tenant {} on {} rows ({} positives)",
            tenantId, X.size(), positives);
        return w;
    }

    /**
     * Fit a Smile 2.6.0 RandomForest. All Smile access is confined to this method
     * and rfPredictBatch(); ANY Throwable (API drift, linkage error) degrades to
     * null so the caller falls back to logistic. Verify at compile time:
     * DataFrame.of(double[][], String...), IntVector.of(String, int[]),
     * DataFrame.merge(...), Formula.lhs(String), RandomForest.fit(Formula, DataFrame).
     */
    private Object smileFit(List<double[]> X, List<Integer> y) {
        try {
            int n = X.size();
            double[][] xArr = new double[n][];
            int[] yArr = new int[n];
            for (int i = 0; i < n; i++) { xArr[i] = X.get(i); yArr[i] = y.get(i); }

            DataFrame df = DataFrame.of(xArr, FEATURE_NAMES)
                                    .merge(IntVector.of("y", yArr));
            return RandomForest.fit(Formula.lhs("y"), df);
        } catch (Throwable t) {
            log.warn("Churn: Smile RandomForest fit failed ({}) — falling back to logistic", t.toString());
            return null;
        }
    }

    /** Batch gradient descent logistic regression with L2 reg. Deterministic, no deps. */
    private double[] fitLogistic(List<double[]> X, List<Integer> y) {
        int n = X.size(), d = N_FEATURES;
        double[] w = new double[d + 1];              // +1 bias at index d
        double lr = 0.1, lambda = 0.001;
        int epochs = 300;
        for (int ep = 0; ep < epochs; ep++) {
            double[] grad = new double[d + 1];
            for (int i = 0; i < n; i++) {
                double[] xi = X.get(i);
                double z = w[d];
                for (int j = 0; j < d; j++) z += w[j] * xi[j];
                double p = sigmoid(z);
                double err = p - y.get(i);
                for (int j = 0; j < d; j++) grad[j] += err * xi[j];
                grad[d] += err;
            }
            for (int j = 0; j < d; j++) w[j] -= lr * (grad[j] / n + lambda * w[j]);
            w[d] -= lr * (grad[d] / n);
        }
        return w;
    }

    // ── Scoring ────────────────────────────────────────────────────────────

    private int scoreAll(Long tenantId, LocalDate asOf, Object model) {
        Map<Long, double[]> feats = extractFeatures(tenantId, asOf);
        if (feats.isEmpty()) return 0;

        // Stable ordering so batch prediction lines up with merchant ids.
        List<Long> ids = new ArrayList<>(feats.keySet());
        double[][] xAll = new double[ids.size()][];
        for (int i = 0; i < ids.size(); i++) xAll[i] = feats.get(ids.get(i));

        double[] probs = null;
        String modelVersion = null;
        String scoredBy = "HEURISTIC";

        if (model instanceof RandomForest) {
            probs = rfPredictBatch((RandomForest) model, xAll);
            if (probs != null) { modelVersion = MODEL_VERSION_RF; scoredBy = "MODEL"; }
        }
        if (probs == null && model instanceof double[]) {
            double[] w = (double[]) model;
            if (w.length == N_FEATURES + 1) {
                probs = new double[xAll.length];
                for (int i = 0; i < xAll.length; i++) {
                    double z = w[N_FEATURES];
                    for (int j = 0; j < N_FEATURES; j++) z += w[j] * xAll[i][j];
                    probs[i] = sigmoid(z);
                }
                modelVersion = MODEL_VERSION_LR; scoredBy = "MODEL";
            }
        }
        if (probs == null) {
            probs = new double[xAll.length];
            for (int i = 0; i < xAll.length; i++) probs[i] = heuristicProb(xAll[i]);
            modelVersion = MODEL_VERSION_LR; scoredBy = "HEURISTIC";
        }

        int written = 0;
        for (int i = 0; i < ids.size(); i++) {
            double prob = clamp(probs[i], 0, 1);
            String band = prob >= 0.66 ? "HIGH" : prob >= 0.33 ? "MEDIUM" : "LOW";
            String reason = topReason(xAll[i]);
            written += upsertScore(tenantId, ids.get(i), asOf, prob, band, reason, modelVersion, scoredBy);
        }
        log.info("Churn: scored {} merchant(s) for tenant {} as of {} ({} / {})",
            written, tenantId, asOf, scoredBy, modelVersion);
        return written;
    }

    /**
     * Predict P(churn) for all rows with a Smile RandomForest. Confined Smile
     * surface; any Throwable → null → caller falls through to logistic/heuristic.
     * A dummy y column keeps the schema identical to training. Verify at compile:
     * df.get(int) → Tuple, rf.predict(Tuple, double[] posteriori).
     */
    private double[] rfPredictBatch(RandomForest rf, double[][] xAll) {
        try {
            DataFrame df = DataFrame.of(xAll, FEATURE_NAMES)
                                    .merge(IntVector.of("y", new int[xAll.length]));
            double[] out = new double[xAll.length];
            double[] posteriori = new double[2];
            for (int i = 0; i < xAll.length; i++) {
                int cls = rf.predict(df.get(i), posteriori);
                out[i] = (posteriori.length > 1 && !Double.isNaN(posteriori[1]))
                        ? posteriori[1] : (cls == 1 ? 1.0 : 0.0);
            }
            return out;
        } catch (Throwable t) {
            log.warn("Churn: Smile RandomForest predict failed ({}) — falling back", t.toString());
            return null;
        }
    }

    /**
     * Heuristic fallback when no model exists: blend recency (f2), inverse trend
     * (1-f0), MoM decline (f3), and inverse count-trend (1-f5). Produces a sensible
     * 0..1 risk without any training.
     */
    private static double heuristicProb(double[] x) {
        double risk = 0.40 * x[2]              // days since last txn
                    + 0.25 * (1 - x[0])        // volume trend down
                    + 0.20 * x[3]              // MoM decline
                    + 0.15 * (1 - x[5]);       // txn-count trend down
        return clamp(risk, 0, 1);
    }

    /** Human-readable primary driver for the UI. */
    private static String topReason(double[] x) {
        // map each risk contribution → label, pick the largest
        double[] contrib = { x[2], (1 - x[0]), x[3], (1 - x[5]) };
        String[] labels = {
            "No recent transactions",
            "Volume trending down",
            "Month-over-month decline",
            "Fewer transactions lately"
        };
        int best = 0; double bv = contrib[0];
        for (int i = 1; i < contrib.length; i++) if (contrib[i] > bv) { bv = contrib[i]; best = i; }
        return labels[best];
    }

    private int upsertScore(Long tenantId, long merchantId, LocalDate asOf,
                            double prob, String band, String reason,
                            String modelVersion, String scoredBy) {
        return jdbc.update(
            "INSERT INTO merchant_churn_score " +
            "(tenant_id, merchant_id, calc_date, churn_probability, risk_band, top_reason, model_version, scored_by, created_at) " +
            "VALUES (?,?,?,?,?,?,?,?, NOW()) " +
            "ON CONFLICT (tenant_id, merchant_id, calc_date) DO UPDATE SET " +
            "  churn_probability = EXCLUDED.churn_probability, risk_band = EXCLUDED.risk_band, " +
            "  top_reason = EXCLUDED.top_reason, model_version = EXCLUDED.model_version, " +
            "  scored_by = EXCLUDED.scored_by, created_at = NOW()",
            tenantId, merchantId, java.sql.Date.valueOf(asOf),
            bd(prob, 4), band, reason, modelVersion, scoredBy);
    }

    // ── Labels from merchant_activity_summary ──────────────────────────────

    /**
     * merchantId → 1 if the snapshot nearest AT/AFTER `target` (within
     * LABEL_MATCH_WINDOW_DAYS) is DORMANT, else 0. Merchants with no snapshot in
     * that window are omitted (unknown outcome — excluded from training).
     */
    private Map<Long, Integer> labelsNear(Long tenantId, LocalDate target) {
        String sql =
            "SELECT a.merchant_id, a.status FROM merchant_activity_summary a " +
            "WHERE a.tenant_id = ? " +
            "  AND a.calc_date = (SELECT MIN(a2.calc_date) FROM merchant_activity_summary a2 " +
            "                     WHERE a2.tenant_id = a.tenant_id AND a2.merchant_id = a.merchant_id " +
            "                       AND a2.calc_date >= ? AND a2.calc_date <= ?)";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId,
            java.sql.Date.valueOf(target),
            java.sql.Date.valueOf(target.plusDays(LABEL_MATCH_WINDOW_DAYS)));
        Map<Long, Integer> out = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> r : rows) {
            Object mid = r.get("merchant_id");
            if (mid == null) continue;
            String status = String.valueOf(r.get("status"));
            out.put(((Number) mid).longValue(), "DORMANT".equalsIgnoreCase(status) ? 1 : 0);
        }
        return out;
    }

    // ── Model persistence (format-sniffing loader) ─────────────────────────

    private File modelFile(Long tenantId) {
        return new File(new File(modelDir, String.valueOf(tenantId)), "churn.model");
    }

    /**
     * Load whatever the file holds: (1) Java-serialized RandomForest (current),
     * (2) legacy CSV logistic weights (v1 scorer), (3) null. Never throws.
     */
    private Object loadModel(Long tenantId) {
        File f = modelFile(tenantId);
        if (!f.exists()) return null;

        // Attempt 1: binary (serialized RandomForest).
        try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            Object o = ois.readObject();
            if (o instanceof RandomForest) return o;
        } catch (Throwable ignore) {
            // Not a serialized model (likely legacy CSV) — try text below.
        }

        // Attempt 2: legacy CSV logistic weights.
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            if (line == null || line.isBlank()) return null;
            String[] parts = line.trim().split(",");
            if (parts.length != N_FEATURES + 1) return null;
            double[] w = new double[parts.length];
            for (int i = 0; i < parts.length; i++) w[i] = Double.parseDouble(parts[i]);
            return w;
        } catch (Exception e) {
            log.warn("Churn: could not read model for tenant {} ({}), will retrain", tenantId, e.getMessage());
            return null;
        }
    }

    /** Persist RandomForest as serialized binary, or logistic weights as legacy CSV. */
    private void saveModel(Long tenantId, Object model) {
        File f = modelFile(tenantId);
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("Churn: could not create model dir {} — model not persisted (will retrain each run)", parent);
                return;
            }
            if (model instanceof RandomForest) {
                try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
                    oos.writeObject(model);
                }
                return;
            }
            if (model instanceof double[]) {
                double[] w = (double[]) model;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < w.length; i++) { if (i > 0) sb.append(','); sb.append(w[i]); }
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(f))) {
                    bw.write(sb.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Churn: could not persist model for tenant {} (non-fatal): {}", tenantId, e.getMessage());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private LocalDate maxBusinessDate(Long tenantId) {
        try {
            // total_txns > 0: ancillary-only days (rental/DCC ahead of the
            // transaction file) must not shift every feature/training window.
            return jdbc.queryForObject(
                "SELECT MAX(business_date) FROM sum_daily_merchant "
                + "WHERE tenant_id = ? AND COALESCE(total_txns,0) > 0",
                LocalDate.class, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    private static double daysSince(Object lastActive, LocalDate asOf) {
        if (lastActive == null) return 60; // never active in window ⇒ max recency risk
        LocalDate d;
        if (lastActive instanceof java.sql.Date) d = ((java.sql.Date) lastActive).toLocalDate();
        else if (lastActive instanceof LocalDate) d = (LocalDate) lastActive;
        else return 60;
        long days = java.time.temporal.ChronoUnit.DAYS.between(d, asOf);
        return days < 0 ? 0 : days;
    }

    private static double sigmoid(double z) { return 1.0 / (1.0 + Math.exp(-z)); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static double num(Object o) { return (o instanceof Number) ? ((Number) o).doubleValue() : 0.0; }
    private static BigDecimal bd(double d, int scale) { return BigDecimal.valueOf(d).setScale(scale, RoundingMode.HALF_UP); }
}
