package io.nettriage.core;

import io.nettriage.core.model.Element;
import io.nettriage.core.model.Tunnel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The overlay as learned from telemetry: every tunnel ever seen, indexed, and for each
 * topology element the tunnels that depend on it. Nothing is configured up front; a new
 * tunnel appears here the first time it reports.
 */
public final class Topology {

    private final List<Tunnel> tunnels = new ArrayList<>();
    private final Map<String, Integer> byKey = new HashMap<>();
    private final Map<Element, List<Integer>> covers = new HashMap<>();
    private final Map<Element, int[]> coverCache = new HashMap<>();
    private final Map<String, List<Integer>> bySystemIp = new HashMap<>();
    private final Map<Integer, String> siteRegion = new HashMap<>();

    /** Returns the tunnel's index, registering it on first sight. */
    public int register(Tunnel t) {
        Integer idx = byKey.get(t.key());
        if (idx != null) {
            return idx;
        }
        int i = tunnels.size();
        tunnels.add(t);
        byKey.put(t.key(), i);
        for (Element e : elementsOf(t)) {
            covers.computeIfAbsent(e, k -> new ArrayList<>()).add(i);
            coverCache.remove(e);
        }
        bySystemIp.computeIfAbsent(t.systemIp(), k -> new ArrayList<>()).add(i);
        siteRegion.put(t.siteId(), t.region());
        return i;
    }

    /** The topology elements a tunnel depends on. */
    public static List<Element> elementsOf(Tunnel t) {
        return List.of(
                Element.circuit(t.siteId(), t.color()),
                Element.site(t.siteId()),
                Element.provider(t.color(), t.region()),
                Element.carrier(t.color()),
                Element.hub(t.hub()),
                Element.hubCircuit(t.hub(), t.color()));
    }

    /** How many elements of each topology kind exist. */
    public Map<Element.Kind, Integer> population() {
        Map<Element.Kind, Integer> out = new java.util.EnumMap<>(Element.Kind.class);
        for (Element e : covers.keySet()) {
            out.merge(e.kind(), 1, Integer::sum);
        }
        return out;
    }

    public int size() {
        return tunnels.size();
    }

    public Tunnel tunnel(int idx) {
        return tunnels.get(idx);
    }

    public Integer indexOf(String key) {
        return byKey.get(key);
    }

    /** Tunnels covered by a topology element; empty for unknown elements and changes. */
    public int[] cover(Element e) {
        return coverCache.computeIfAbsent(e, k -> {
            List<Integer> l = covers.get(k);
            return l == null ? new int[0] : l.stream().mapToInt(Integer::intValue).toArray();
        });
    }

    /** Tunnels of the given routers, optionally limited to one transport. */
    public int[] coverOfRouters(Collection<String> systemIps, String color) {
        List<Integer> out = new ArrayList<>();
        for (String ip : systemIps) {
            for (int i : bySystemIp.getOrDefault(ip, List.of())) {
                if (color == null || color.equals(tunnels.get(i).color())) {
                    out.add(i);
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public List<Integer> tunnelsOfRouter(String systemIp) {
        return bySystemIp.getOrDefault(systemIp, List.of());
    }

    public Set<String> regions() {
        Set<String> s = new TreeSet<>();
        tunnels.forEach(t -> s.add(t.region()));
        return s;
    }

    public Set<String> colors() {
        Set<String> s = new LinkedHashSet<>();
        tunnels.forEach(t -> s.add(t.color()));
        return s;
    }

    public Set<String> hubs() {
        Set<String> s = new TreeSet<>();
        tunnels.forEach(t -> s.add(t.hub()));
        return s;
    }

    public String regionOfSite(int siteId) {
        return siteRegion.get(siteId);
    }

    public List<Tunnel> tunnels() {
        return List.copyOf(tunnels);
    }
}
