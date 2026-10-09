/*
 * PerfGate - evaluates a JMeter CSV result file (JTL) against configurable thresholds.
 *
 * Needs only a JDK 11+ (the same Java that runs JMeter). No compilation step:
 *   java scripts/PerfGate.java --jtl results/load.jtl --thresholds config/thresholds-load.properties
 *        [--summary results/perf-gate-summary.txt] [key=value ...overrides]
 *
 * Exit codes (the Jenkinsfile maps them to a build result):
 *   0 = PASS      -> SUCCESS
 *   1 = WARN      -> UNSTABLE   (a "warn" threshold was breached)
 *   2 = FAIL      -> FAILURE    (a "fail" threshold was breached)
 *   3 = ERROR     -> FAILURE    (JTL missing/empty/unreadable, bad arguments or thresholds)
 *
 * Percentiles use the same interpolation as Apache Commons Math (the JMeter dashboard's library),
 * so the gate's numbers line up with the HTML report.
 */
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class PerfGate {

    static final int PASS = 0, WARN = 1, FAIL = 2, ERROR = 3;

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (GateException e) {
            System.out.println("[PERF-GATE] ERROR: " + e.getMessage());
            code = ERROR;
        } catch (Exception e) {
            System.out.println("[PERF-GATE] ERROR: " + e);
            code = ERROR;
        }
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        String jtl = null, thresholdsFile = null, summaryFile = null;
        Properties t = new Properties();
        List<String> overrides = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jtl": jtl = arg(args, ++i); break;
                case "--thresholds": thresholdsFile = arg(args, ++i); break;
                case "--summary": summaryFile = arg(args, ++i); break;
                default:
                    if (args[i].contains("=")) overrides.add(args[i]);
                    else throw new GateException("Unknown argument: " + args[i]);
            }
        }
        if (jtl == null || thresholdsFile == null) {
            throw new GateException("Usage: java PerfGate.java --jtl <file> --thresholds <file> [--summary <file>] [key=value ...]");
        }
        Path thresholdsPath = Paths.get(thresholdsFile);
        if (!Files.isRegularFile(thresholdsPath)) throw new GateException("Thresholds file not found: " + thresholdsPath.toAbsolutePath());
        try (Reader r = Files.newBufferedReader(thresholdsPath, StandardCharsets.UTF_8)) { t.load(r); }
        for (String o : overrides) {
            int eq = o.indexOf('=');
            t.setProperty(o.substring(0, eq).trim(), o.substring(eq + 1).trim());
        }

        Path jtlPath = Paths.get(jtl);
        if (!Files.isRegularFile(jtlPath)) throw new GateException("JTL file not found: " + jtlPath.toAbsolutePath() + " (did JMeter run?)");
        if (Files.size(jtlPath) == 0) throw new GateException("JTL file is empty: " + jtlPath.toAbsolutePath());

        Map<String, Stats> byLabel = new LinkedHashMap<>();
        Stats total = new Stats("TOTAL");
        readJtl(jtlPath, byLabel, total);
        if (total.count() == 0) throw new GateException("JTL has a header but no samples: " + jtlPath.toAbsolutePath());

        // ---------------- evaluate rules ----------------
        List<String> fails = new ArrayList<>(), warns = new ArrayList<>(), passes = new ArrayList<>();

        long minSamples = (long) num(t, "min.samples", 1);
        check(total.count() >= minSamples, fails, passes,
              String.format(Locale.ROOT, "Sample count %d >= min.samples %d", total.count(), minSamples),
              String.format(Locale.ROOT, "Sample count %d < min.samples %d", total.count(), minSamples));

        double errPct = total.errorPct();
        double errFail = num(t, "error.pct.fail", 5), errWarn = num(t, "error.pct.warn", 1);
        if (errPct > errFail) fails.add(String.format(Locale.ROOT, "Error rate %.2f%% > error.pct.fail %.2f%%", errPct, errFail));
        else if (errPct > errWarn) warns.add(String.format(Locale.ROOT, "Error rate %.2f%% > error.pct.warn %.2f%%", errPct, errWarn));
        else passes.add(String.format(Locale.ROOT, "Error rate %.2f%% <= error.pct.warn %.2f%%", errPct, errWarn));

        double p95 = total.percentile(95);
        double p95Fail = num(t, "p95.ms.fail", Double.MAX_VALUE), p95Warn = num(t, "p95.ms.warn", Double.MAX_VALUE);
        if (p95 > p95Fail) fails.add(String.format(Locale.ROOT, "Overall p95 %.0f ms > p95.ms.fail %.0f ms", p95, p95Fail));
        else if (p95 > p95Warn) warns.add(String.format(Locale.ROOT, "Overall p95 %.0f ms > p95.ms.warn %.0f ms", p95, p95Warn));
        else passes.add(String.format(Locale.ROOT, "Overall p95 %.0f ms <= p95.ms.warn %.0f ms", p95, p95Warn));

        for (String key : t.stringPropertyNames()) {
            if (!key.startsWith("p95.ms.label.")) continue;
            String label = key.substring("p95.ms.label.".length());
            double limit = num(t, key, Double.MAX_VALUE);
            Stats s = byLabel.get(label);
            if (s == null) { warns.add("Label '" + label + "' from " + key + " has no samples in the JTL"); continue; }
            double lp95 = s.percentile(95);
            if (lp95 > limit) warns.add(String.format(Locale.ROOT, "%s p95 %.0f ms > %s %.0f ms", label, lp95, key, limit));
            else passes.add(String.format(Locale.ROOT, "%s p95 %.0f ms <= %.0f ms", label, lp95, limit));
        }

        long critMax = (long) num(t, "critical.max.errors", 0);
        for (String label : t.getProperty("critical.labels", "").split(",")) {
            label = label.trim();
            if (label.isEmpty()) continue;
            Stats s = byLabel.get(label);
            if (s == null) { fails.add("Critical transaction '" + label + "' has no samples (did it run?)"); continue; }
            check(s.errors <= critMax, fails, passes,
                  String.format(Locale.ROOT, "Critical %s errors %d <= critical.max.errors %d", label, s.errors, critMax),
                  String.format(Locale.ROOT, "Critical %s errors %d > critical.max.errors %d", label, s.errors, critMax));
        }

        int code = !fails.isEmpty() ? FAIL : !warns.isEmpty() ? WARN : PASS;
        String verdict = code == FAIL ? "FAIL (build -> FAILURE)" : code == WARN ? "WARN (build -> UNSTABLE)" : "PASS (build -> SUCCESS)";

        // ---------------- report ----------------
        StringBuilder out = new StringBuilder();
        out.append("==================== PERFORMANCE GATE ====================\n");
        out.append("JTL        : ").append(jtlPath).append('\n');
        out.append("Thresholds : ").append(thresholdsPath).append(overrides.isEmpty() ? "" : "  overrides=" + overrides).append('\n');
        out.append(String.format(Locale.ROOT, "%-22s %8s %7s %8s %8s %8s %8s %8s %9s%n",
                "Label", "Samples", "Err%", "Avg", "Median", "P90", "P95", "P99", "Req/s"));
        List<Stats> rows = new ArrayList<>(byLabel.values());
        rows.add(total);
        for (Stats s : rows) {
            out.append(String.format(Locale.ROOT, "%-22s %8d %6.2f%% %8.0f %8.0f %8.0f %8.0f %8.0f %9.2f%n",
                    truncate(s.label, 22), s.count(), s.errorPct(), s.avg(), s.percentile(50),
                    s.percentile(90), s.percentile(95), s.percentile(99), s.throughput()));
        }
        out.append("(times in ms)\n----------------------------------------------------------\n");
        for (String f : fails) out.append("[FAIL] ").append(f).append('\n');
        for (String w : warns) out.append("[WARN] ").append(w).append('\n');
        for (String p : passes) out.append("[PASS] ").append(p).append('\n');
        out.append("RESULT     : ").append(verdict).append('\n');
        out.append("==========================================================\n");
        System.out.print(out);
        if (summaryFile != null) {
            Path sp = Paths.get(summaryFile);
            if (sp.getParent() != null) Files.createDirectories(sp.getParent());
            Files.write(sp, out.toString().getBytes(StandardCharsets.UTF_8));
        }
        return code;
    }

    // ---------------- JTL parsing ----------------
    static void readJtl(Path jtl, Map<String, Stats> byLabel, Stats total) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(jtl, StandardCharsets.UTF_8)) {
            CsvReader csv = new CsvReader(br);
            List<String> header = csv.next();
            if (header == null) throw new GateException("JTL is empty: " + jtl);
            int iTs = header.indexOf("timeStamp"), iEl = header.indexOf("elapsed"),
                iLb = header.indexOf("label"), iOk = header.indexOf("success");
            if (iTs < 0 || iEl < 0 || iLb < 0 || iOk < 0) {
                throw new GateException("JTL must be CSV with a header row containing timeStamp,elapsed,label,success. "
                        + "Found: " + header + ". Run JMeter with -q config/jmeter-ci.properties");
            }
            List<String> row;
            long line = 1;
            while ((row = csv.next()) != null) {
                line++;
                if (row.size() == 1 && row.get(0).isEmpty()) continue;
                if (row.size() < header.size()) {
                    System.out.println("[PERF-GATE] WARN: skipping malformed JTL record #" + line + " (" + row.size() + " fields)");
                    continue;
                }
                long ts, el;
                try {
                    ts = Long.parseLong(row.get(iTs).trim());
                    el = Long.parseLong(row.get(iEl).trim());
                } catch (NumberFormatException e) {
                    System.out.println("[PERF-GATE] WARN: skipping JTL record #" + line + " with non-numeric timeStamp/elapsed");
                    continue;
                }
                boolean ok = "true".equalsIgnoreCase(row.get(iOk).trim());
                String label = row.get(iLb);
                byLabel.computeIfAbsent(label, Stats::new).add(ts, el, ok);
                total.add(ts, el, ok);
            }
        }
    }

    /** Minimal RFC-4180 CSV reader (JMeter quotes fields that contain commas, quotes or newlines). */
    static final class CsvReader {
        private final Reader in;
        private int peeked = -2;
        CsvReader(Reader in) { this.in = in; }
        private int read() throws IOException {
            if (peeked != -2) { int c = peeked; peeked = -2; return c; }
            return in.read();
        }
        List<String> next() throws IOException {
            int c = read();
            if (c == -1) return null;
            List<String> fields = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean quoted = false;
            while (true) {
                if (quoted) {
                    if (c == -1) break;
                    if (c == '"') {
                        int n = read();
                        if (n == '"') sb.append('"');
                        else { quoted = false; peeked = n; }
                    } else sb.append((char) c);
                } else {
                    if (c == -1 || c == '\n') break;
                    if (c == '\r') { int n = read(); if (n != '\n') peeked = n; break; }
                    if (c == '"' && sb.length() == 0) quoted = true;
                    else if (c == ',') { fields.add(sb.toString()); sb.setLength(0); }
                    else sb.append((char) c);
                }
                c = read();
            }
            fields.add(sb.toString());
            return fields;
        }
    }

    // ---------------- statistics ----------------
    static final class Stats {
        final String label;
        long[] elapsed = new long[1024];
        int n;
        long errors, minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE, sum;
        boolean sorted;
        Stats(String label) { this.label = label; }
        void add(long ts, long el, boolean ok) {
            if (n == elapsed.length) elapsed = Arrays.copyOf(elapsed, n * 2);
            elapsed[n++] = el;
            sum += el;
            if (!ok) errors++;
            minTs = Math.min(minTs, ts);
            maxTs = Math.max(maxTs, ts + el);
            sorted = false;
        }
        long count() { return n; }
        double avg() { return n == 0 ? 0 : (double) sum / n; }
        double errorPct() { return n == 0 ? 0 : errors * 100.0 / n; }
        double throughput() {
            double secs = (maxTs - minTs) / 1000.0;
            return secs <= 0 ? n : n / secs;
        }
        /** Commons Math "legacy" estimation: pos = p * (n + 1) / 100, linear interpolation. */
        double percentile(double p) {
            if (n == 0) return 0;
            if (!sorted) { Arrays.sort(elapsed, 0, n); sorted = true; }
            if (n == 1) return elapsed[0];
            double pos = p * (n + 1) / 100.0;
            if (pos < 1) return elapsed[0];
            if (pos >= n) return elapsed[n - 1];
            int lo = (int) Math.floor(pos);
            double d = pos - lo;
            return elapsed[lo - 1] + d * (elapsed[lo] - elapsed[lo - 1]);
        }
    }

    // ---------------- helpers ----------------
    static final class GateException extends RuntimeException {
        GateException(String m) { super(m); }
    }

    static String arg(String[] a, int i) {
        if (i >= a.length) throw new GateException("Missing value for " + a[i - 1]);
        return a[i];
    }

    static double num(Properties p, String key, double def) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new GateException("Threshold '" + key + "' is not a number: '" + v + "'");
        }
    }

    static void check(boolean ok, List<String> fails, List<String> passes, String passMsg, String failMsg) {
        if (ok) passes.add(passMsg); else fails.add(failMsg);
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }
}
