package io.nettriage.agent;

import io.nettriage.bench.Scenario;
import io.nettriage.bench.ScenarioFactory;
import io.nettriage.bench.SimFleet;
import io.nettriage.core.TriageEngine;
import io.nettriage.core.tools.ActionLedger;
import io.nettriage.core.tools.TriageTools;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Scores the agent on benchmark scenarios with known root causes: does its submitted
 * root cause match the truth, and does the grounding verifier accept the write-up?
 * Needs ANTHROPIC_API_KEY; costs a few cents per scenario.
 *
 * <pre>  java -cp agent.jar io.nettriage.agent.AgentEval [--per-type 2] [--seed 9001] [--out agent-eval.json]</pre>
 */
public final class AgentEval {

    static final List<String> TYPES = List.of("provider-brownout", "circuit-blackout", "site-down", "hub-circuit",
            "bad-rollout", "red-herring-rollout", "compound");

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        int perType = Integer.parseInt(opt.getOrDefault("per-type", "2"));
        long seed = Long.parseLong(opt.getOrDefault("seed", "9001"));
        JsonMapper json = JsonMapper.builder().build();
        AnthropicClient client = AnthropicClient.fromEnvironment(json);

        List<Map<String, Object>> rows = new ArrayList<>();
        int correct = 0;
        int grounded = 0;
        int total = 0;
        for (String type : TYPES) {
            for (int k = 0; k < perType; k++) {
                Scenario sc = new ScenarioFactory(new SimFleet(200)).make(type, seed + k);
                TriageEngine engine = replay(sc);
                TriageAgent agent = new TriageAgent(client, engine, new TriageTools(engine, new ActionLedger()), json, 14);
                TriageAgent.Result r = agent.run("Something is wrong with the network. Investigate the open "
                        + "incidents and submit a diagnosis for each.");
                Set<String> said = new java.util.HashSet<>();
                r.diagnoses().forEach(d -> said.add(d.rootCause()));
                boolean right = said.equals(Set.copyOf(sc.truth()));
                boolean allGrounded = !r.grounding().isEmpty() && r.grounding().stream().allMatch(GroundingVerifier.Report::grounded);
                total++;
                correct += right ? 1 : 0;
                grounded += allGrounded ? 1 : 0;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", type);
                row.put("seed", seed + k);
                row.put("truth", sc.truth());
                row.put("engine", engine.incidents(false).stream().map(i -> i.rootCause()).toList());
                row.put("agent", r.diagnoses());
                row.put("rootCauseCorrect", right);
                row.put("grounded", allGrounded);
                row.put("grounding", r.grounding());
                row.put("toolCalls", r.steps().size());
                row.put("turns", r.turns());
                row.put("inputTokens", r.inputTokens());
                row.put("outputTokens", r.outputTokens());
                rows.add(row);
                System.out.printf("%-20s seed=%d correct=%s grounded=%s tools=%d%n", type, seed + k, right, allGrounded,
                        r.steps().size());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", client.model());
        out.put("scenarios", total);
        out.put("rootCauseCorrectPct", total == 0 ? 0 : Math.round(1000.0 * correct / total) / 10.0);
        out.put("groundedPct", total == 0 ? 0 : Math.round(1000.0 * grounded / total) / 10.0);
        out.put("runs", rows);
        File f = new File(opt.getOrDefault("out", "agent-eval.json"));
        JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValue(f, out);
        System.out.printf("root cause correct %d/%d, grounded %d/%d; wrote %s%n", correct, total, grounded, total, f);
    }

    /** Runs a scenario up to its evaluation time and returns the engine in that state. */
    static TriageEngine replay(Scenario sc) {
        SimFleet fleet = new SimFleet(200);
        SplittableRandom rnd = new SplittableRandom(sc.seed());
        TriageEngine engine = new TriageEngine();
        int ci = 0;
        for (int w = 1; w <= sc.windows(); w++) {
            long end = sc.startMs() + w * SimFleet.WINDOW_MS;
            if (end > sc.evalAt()) {
                break;
            }
            while (ci < sc.changes().size() && sc.changes().get(ci).appliedAt() <= end) {
                engine.addChange(sc.changes().get(ci++));
            }
            engine.processWindow(end, fleet.window(end, sc.impairments(), rnd));
        }
        return engine;
    }
}
