package io.nettriage.service.ingest;

import io.nettriage.core.model.Tunnel;
import io.nettriage.core.model.TunnelWindow;

/**
 * WanPulse's {@code tunnel.health} record, as it appears on the wire. Only the fields
 * NetTriage needs are declared; the rest (SLA verdicts, sample counts) are ignored.
 */
public record TunnelHealthMessage(
        String tunnelKey,
        int siteId,
        String region,
        String localSystemIp,
        String localColor,
        String remoteSystemIp,
        long windowEnd,
        long probesSent,
        double lossPct,
        Double latencyMs,
        Double jitterMs,
        boolean sufficientData) {

    public TunnelWindow toWindow() {
        return new TunnelWindow(new Tunnel(tunnelKey, siteId, localSystemIp, region, localColor, remoteSystemIp),
                windowEnd, probesSent, lossPct, latencyMs, jitterMs, sufficientData);
    }
}
