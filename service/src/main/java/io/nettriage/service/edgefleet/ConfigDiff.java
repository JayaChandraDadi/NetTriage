package io.nettriage.service.edgefleet;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural diff of two EdgeFleet configs, matching transports by color so that
 * reordering them is not a change. Also reports which transport a change touches,
 * which decides which tunnels a rollout could have affected.
 */
public final class ConfigDiff {

    private static final Pattern TRANSPORT = Pattern.compile("^transports\\[color=([^\\]]+)\\]");

    public record Result(List<String> lines, String color) {
    }

    private ConfigDiff() {
    }

    public static Result diff(JsonNode before, JsonNode after) {
        List<String> lines = new ArrayList<>();
        walk("", before, after, lines);
        Set<String> colors = new LinkedHashSet<>();
        boolean shared = false;
        for (String l : lines) {
            Matcher m = TRANSPORT.matcher(l.substring(2));
            if (m.find()) {
                colors.add(m.group(1));
            } else {
                shared = true;
            }
        }
        return new Result(lines, !shared && colors.size() == 1 ? colors.iterator().next() : null);
    }

    private static void walk(String path, JsonNode a, JsonNode b, List<String> out) {
        if (a == null || a.isMissingNode() || a.isNull()) {
            if (b != null && !b.isMissingNode() && !b.isNull()) {
                out.add("+ " + path + ": " + b);
            }
            return;
        }
        if (b == null || b.isMissingNode() || b.isNull()) {
            out.add("- " + path + ": " + a);
            return;
        }
        if (a.isObject() && b.isObject()) {
            Set<String> keys = new LinkedHashSet<>(a.propertyNames());
            keys.addAll(b.propertyNames());
            for (String k : keys) {
                walk(path.isEmpty() ? k : path + "." + k, a.get(k), b.get(k), out);
            }
        } else if (a.isArray() && b.isArray() && keyedByColor(a) && keyedByColor(b)) {
            Map<String, JsonNode> am = byColor(a);
            Map<String, JsonNode> bm = byColor(b);
            Set<String> colors = new LinkedHashSet<>(am.keySet());
            colors.addAll(bm.keySet());
            for (String c : colors) {
                walk(path + "[color=" + c + "]", am.get(c), bm.get(c), out);
            }
        } else if (!a.equals(b)) {
            out.add("~ " + path + ": " + a + " -> " + b);
        }
    }

    private static boolean keyedByColor(JsonNode arr) {
        for (JsonNode n : arr) {
            if (!n.isObject() || !n.has("color")) {
                return false;
            }
        }
        return !arr.isEmpty();
    }

    private static Map<String, JsonNode> byColor(JsonNode arr) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        arr.forEach(n -> m.put(n.get("color").asString(), n));
        return m;
    }
}
