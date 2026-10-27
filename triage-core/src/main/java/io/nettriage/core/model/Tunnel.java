package io.nettriage.core.model;

/**
 * One overlay tunnel: from a branch router over one transport (color) to a hub.
 *
 * @param key      stable identifier, e.g. {@code 10.1.0.3:mpls->10.0.0.1:mpls}
 * @param siteId   branch site
 * @param systemIp branch router's system IP, which is also how config changes are matched
 * @param region   branch region
 * @param color    transport color (mpls, biz-internet, lte)
 * @param hub      system IP of the hub at the far end
 */
public record Tunnel(String key, int siteId, String systemIp, String region, String color, String hub) {
}
