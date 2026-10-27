package io.nettriage.bench;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.localize.Localizer;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Runs every scenario type many times with different seeds and reports how often each
 * method names exactly the right root cause, how fast the engine gets there, and how
 * often it raises an incident that isn't real.
 *
 * <pre>
 *   java -jar bench.jar [--sites 200] [--per-type 40] [--seed 1] [--out bench-results.json]
 *                       [--sweep] [--scale] [--verbose] [--types a,b]
 * </pre>
 */
public final class Bench {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
                opt.put(args[i].substring(2), flag ? "true" : args[++i]);
            }
        }
        int sites = Integer.parseInt(opt.getOrDefault("sites", "200"));
        int perType = Integer.parseInt(opt.getOrDefault("per-type", "40"));
        long seed = Long.parseLong(opt.getOrDefault("seed", "1"));
        List<String> types = opt.containsKey("types") ? List.of(opt.get("types").split(",")) : ScenarioFactory.TYPES;
        boolean verbose = opt.containsKey("verbose");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", Map.of("sites", sites, "tunnels", new SimFleet(sites).tunnels().size(), "perType", perType,
                "seed", seed, "windowSeconds", SimFleet.WINDOW_MS / 1000, "evalSecondsAfterFaultStart",
                ScenarioFactory.EVAL_DELAY_MS / 1000, "cpus", Runtime.getRuntime().availableProcessors()));

        long t0 = System.nanoTime();
        List<Runner.Result> results = runAll(types, perType, seed, sites, TriageEngine.Settings.defaults());
        double seconds = (System.nanoTime() - t0) / 1e9;
        out.put("results", summarize(types, results));
        out.put("runSeconds", Math.round(seconds));
        print(types, results);
        if (verbose) {
            results.stream().filter(r -> r.falseIncidents() > 0).forEach(r -> System.out.printf(
                    "FALSE %-20s seed=%-3d truth=%s false=%s%n", r.type(), r.seed(), r.truth(), r.falseRoots()));
            results.stream().filter(r -> !r.engineExact()).forEach(r -> System.out.printf(
                    "MISS %-20s seed=%-3d truth=%s engine=%s localizer=%s diag=%s note=%s%n", r.type(), r.seed(),
                    r.truth(), r.engineRoots(), r.localizerRoots(), r.diagnosedAfterMs(), r.note()));
        }

        if (opt.containsKey("sweep")) {
            List<Map<String, Object>> sweep = new ArrayList<>();
            for (double lambda : new double[] {1, 2, 3, 4, 5, 6}) {
                for (double eps : new double[] {0.002, 0.005, 0.01}) {
                    TriageEngine.Settings d = TriageEngine.Settings.defaults();
                    TriageEngine.Settings s = d.withLocalizer(new Localizer.Settings(eps, lambda,
                            d.localizer().lambdaActive(), d.localizer().maxCauses()));
                    List<Runner.Result> rs = runAll(types, Math.max(10, perType / 2), seed + 1000, sites, s);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("lambda", lambda);
                    row.put("eps", eps);
                    row.put("faultExactPct", pct(rs.stream().filter(r -> !r.truth().isEmpty()).toList(), Runner.Result::engineExact));
                    row.put("falseIncidentsPerRun", round2(rs.stream().mapToInt(Runner.Result::falseIncidents).average().orElse(0)));
                    sweep.add(row);
                    System.out.printf("sweep lambda=%-4s eps=%-6s exact=%5.1f%% falseIncidents/run=%.2f%n", lambda, eps,
                            row.get("faultExactPct"), row.get("falseIncidentsPerRun"));
                }
            }
            out.put("sensitivity", sweep);
        }

        if (opt.containsKey("scale")) {
            List<Map<String, Object>> scale = new ArrayList<>();
            for (int n : new int[] {200, 1000, 2500, 5000}) {
                ScenarioFactory f = new ScenarioFactory(new SimFleet(n));
                Runner.Result r = Runner.run(f.make("compound", seed), n, TriageEngine.Settings.defaults());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sites", n);
                row.put("tunnels", r.tunnels());
                row.put("meanWindowMs", round2(r.meanWindowMs()));
                row.put("exact", r.engineExact());
                scale.add(row);
                System.out.printf("scale sites=%-5d tunnels=%-6d mean diagnosis time per 10 s window=%.2f ms exact=%s%n",
                        n, r.tunnels(), r.meanWindowMs(), r.engineExact());
            }
            out.put("scale", scale);
        }

        File file = new File(opt.getOrDefault("out", "bench-results.json"));
        JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValue(file, out);
        System.out.printf("%d scenarios in %.0f s; wrote %s%n", results.size(), seconds, file.getPath());
    }

    static List<Runner.Result> runAll(List<String> types, int perType, long seed, int sites, TriageEngine.Settings s) {
        ScenarioFactory factory = new ScenarioFactory(new SimFleet(sites));
        List<Scenario> scenarios = new ArrayList<>();
        for (String type : types) {
            for (int i = 0; i < perType; i++) {
                scenarios.add(factory.make(type, seed + i));
            }
        }
        Runner.Result[] results = new Runner.Result[scenarios.size()];
        IntStream.range(0, scenarios.size()).parallel().forEach(i -> results[i] = Runner.run(scenarios.get(i), sites, s));
        return List.of(results);
    }

    static List<Map<String, Object>> summarize(List<String> types, List<Runner.Result> all) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String type : types) {
            rows.add(row(type, all.stream().filter(r -> r.type().equals(type)).toList()));
        }
        rows.add(row("all faults", all.stream().filter(r -> !r.truth().isEmpty()).toList()));
        rows.add(row("no fault", all.stream().filter(r -> r.truth().isEmpty()).toList()));
        return rows;
    }

    static Map<String, Object> row(String name, List<Runner.Result> rs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", name);
        m.put("runs", rs.size());
        if (rs.isEmpty()) {
            return m;
        }
        m.put("note", rs.get(0).note());
        m.put("nettriageExactPct", pct(rs, Runner.Result::engineExact));
        m.put("thresholdCoverExactPct", pct(rs, r -> Set(r.thresholdCover()).equals(Set(r.truth()))));
        m.put("thresholdCover50ExactPct", pct(rs, r -> Set(r.thresholdCoverLoose()).equals(Set(r.truth()))));
        m.put("alarmsOnlyExactPct", pct(rs, r -> Set(r.alarmsOnly()).equals(Set(r.truth()))));
        m.put("blameLatestChangeExactPct", pct(rs, r -> Set(r.blameChange()).equals(Set(r.truth()))));
        List<Long> diag = new ArrayList<>();
        List<Long> res = new ArrayList<>();
        int missed = 0;
        for (Runner.Result r : rs) {
            for (String t : r.truth()) {
                Long d = r.diagnosedAfterMs().get(t);
                if (d == null) {
                    missed++;
                } else {
                    diag.add(d);
                }
                Long x = r.resolvedAfterMs().get(t);
                if (x != null) {
                    res.add(x);
                }
            }
        }
        Collections.sort(diag);
        Collections.sort(res);
        if (!diag.isEmpty()) {
            m.put("timeToDiagnoseP50s", diag.get(diag.size() / 2) / 1000.0);
            m.put("timeToDiagnoseP90s", diag.get((int) Math.min(diag.size() - 1, Math.floor(diag.size() * 0.9))) / 1000.0);
        }
        m.put("faultsNeverDiagnosed", missed);
        if (!res.isEmpty()) {
            m.put("resolvedAfterFaultEndP50s", res.get(res.size() / 2) / 1000.0);
        }
        int falseInc = rs.stream().mapToInt(Runner.Result::falseIncidents).sum();
        int alerts = rs.stream().mapToInt(Runner.Result::alerts).sum();
        int incidents = rs.stream().mapToInt(Runner.Result::incidentsOpened).sum();
        m.put("falseIncidents", falseInc);
        m.put("runsWithFalseIncident", rs.stream().filter(r -> r.falseIncidents() > 0).count());
        m.put("tunnelAlerts", alerts);
        m.put("incidentsOpened", incidents);
        m.put("alertsPerIncident", incidents == 0 ? null : round2((double) alerts / incidents));
        Map<String, Long> conf = new LinkedHashMap<>();
        rs.stream().filter(Runner.Result::engineExact).filter(r -> r.confidenceAtEval() != null)
                .forEach(r -> conf.merge(r.confidenceAtEval(), 1L, Long::sum));
        m.put("confidenceWhenRight", conf);
        return m;
    }

    private static java.util.Set<String> Set(List<String> l) {
        return java.util.Set.copyOf(l);
    }

    static double pct(List<Runner.Result> rs, java.util.function.Predicate<Runner.Result> p) {
        return rs.isEmpty() ? 0 : Math.round(1000.0 * rs.stream().filter(p).count() / rs.size()) / 10.0;
    }

    static double round2(double x) {
        return Math.round(x * 100) / 100.0;
    }

    static void print(List<String> types, List<Runner.Result> all) {
        System.out.printf("%-22s %4s %9s %9s %9s %9s %9s %8s %8s %6s %8s%n", "scenario", "runs", "NetTriage", "thresh80",
                "thresh50", "alarms", "blameChg", "diagP50", "diagP90", "false", "alrt/inc");
        for (Map<String, Object> r : summarize(types, all)) {
            System.out.printf("%-22s %4s %8s%% %8s%% %8s%% %8s%% %8s%% %7ss %7ss %6s %8s%n", r.get("type"), r.get("runs"),
                    r.get("nettriageExactPct"), r.get("thresholdCoverExactPct"), r.get("thresholdCover50ExactPct"),
                    r.get("alarmsOnlyExactPct"),
                    r.get("blameLatestChangeExactPct"), r.getOrDefault("timeToDiagnoseP50s", "-"),
                    r.getOrDefault("timeToDiagnoseP90s", "-"), r.get("falseIncidents"), r.get("alertsPerIncident"));
        }
    }
}
