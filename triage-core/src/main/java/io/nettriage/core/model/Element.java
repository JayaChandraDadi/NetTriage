package io.nettriage.core.model;

import java.util.Objects;

/**
 * Something that can fail and take a set of tunnels down with it. Each element
 * "covers" the tunnels that depend on it:
 *
 * <ul>
 *   <li>{@code CIRCUIT}: one site's access circuit on one transport (cut cable, dead LTE modem)</li>
 *   <li>{@code SITE}: the branch router itself, all its transports</li>
 *   <li>{@code PROVIDER}: one transport in one region (an ISP brownout)</li>
 *   <li>{@code CARRIER}: one transport everywhere (a national carrier problem)</li>
 *   <li>{@code HUB}: a hub router, every tunnel that ends there</li>
 *   <li>{@code HUB_CIRCUIT}: a hub's circuit on one transport</li>
 *   <li>{@code CHANGE}: the routers that applied one rollout, on the transport it touched</li>
 * </ul>
 *
 * Unused fields are null.
 */
public record Element(Kind kind, Integer siteId, String color, String region, String hub, String deploymentId) {

    /**
     * Declared in order of preference when two elements cover exactly the same tunnels
     * and the data cannot tell them apart.
     */
    public enum Kind { CHANGE, CIRCUIT, SITE, HUB_CIRCUIT, HUB, PROVIDER, CARRIER }

    public Element {
        Objects.requireNonNull(kind);
    }

    public static Element site(int siteId) {
        return new Element(Kind.SITE, siteId, null, null, null, null);
    }

    public static Element circuit(int siteId, String color) {
        return new Element(Kind.CIRCUIT, siteId, color, null, null, null);
    }

    public static Element provider(String color, String region) {
        return new Element(Kind.PROVIDER, null, color, region, null, null);
    }

    public static Element carrier(String color) {
        return new Element(Kind.CARRIER, null, color, null, null, null);
    }

    public static Element hub(String hub) {
        return new Element(Kind.HUB, null, null, null, hub, null);
    }

    public static Element hubCircuit(String hub, String color) {
        return new Element(Kind.HUB_CIRCUIT, null, color, null, hub, null);
    }

    /** {@code color} null means every transport on the routers that applied the change. */
    public static Element change(String deploymentId, String color) {
        return new Element(Kind.CHANGE, null, color, null, null, deploymentId);
    }

    /** Stable, parseable identifier, e.g. {@code provider/biz-internet/us-west}. */
    public String id() {
        return switch (kind) {
            case SITE -> "site/" + siteId;
            case CIRCUIT -> "site/" + siteId + "/" + color;
            case PROVIDER -> "provider/" + color + "/" + region;
            case CARRIER -> "carrier/" + color;
            case HUB -> "hub/" + hub;
            case HUB_CIRCUIT -> "hub/" + hub + "/" + color;
            case CHANGE -> "change/" + deploymentId + (color == null ? "" : "/" + color);
        };
    }

    /** Inverse of {@link #id()}. */
    public static Element parse(String id) {
        String[] p = id.split("/");
        try {
            return switch (p[0]) {
                case "site" -> p.length == 2 ? site(Integer.parseInt(p[1])) : circuit(Integer.parseInt(p[1]), p[2]);
                case "provider" -> provider(p[1], p[2]);
                case "carrier" -> carrier(p[1]);
                case "hub" -> p.length == 2 ? hub(p[1]) : hubCircuit(p[1], p[2]);
                case "change" -> change(p[1], p.length > 2 ? p[2] : null);
                default -> throw new IllegalArgumentException("unknown element kind in " + id);
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not an element id: " + id
                    + " (expected e.g. site/102, site/102/lte, provider/mpls/us-west, carrier/lte, hub/10.0.0.1,"
                    + " hub/10.0.0.1/mpls, change/<deployment>/biz-internet)");
        }
    }

    /** Plain-language name for people. */
    public String describe() {
        return switch (kind) {
            case SITE -> "router at site " + siteId;
            case CIRCUIT -> color + " circuit at site " + siteId;
            case PROVIDER -> color + " provider in " + region;
            case CARRIER -> color + " in every region";
            case HUB -> "hub " + hub;
            case HUB_CIRCUIT -> color + " circuit at hub " + hub;
            case CHANGE -> "config rollout " + shortId(deploymentId) + (color == null ? "" : " on " + color);
        };
    }

    static String shortId(String deploymentId) {
        return deploymentId != null && deploymentId.length() > 8 ? deploymentId.substring(0, 8) : deploymentId;
    }

    @Override
    public String toString() {
        return id();
    }
}
