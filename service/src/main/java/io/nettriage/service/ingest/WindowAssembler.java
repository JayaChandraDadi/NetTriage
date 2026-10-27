package io.nettriage.service.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nettriage.core.TriageEngine;
import io.nettriage.core.Views;
import io.nettriage.core.model.TunnelWindow;
import io.nettriage.service.NetTriageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Groups incoming tunnel records into closed windows and hands each window to the
 * engine once, in order. WanPulse publishes a window's records together when the
 * window closes, so a window is complete once its records stop arriving for a moment.
 * Records for a window already diagnosed are counted as late and dropped.
 */
@Component
public class WindowAssembler {

    private static final Logger log = LoggerFactory.getLogger(WindowAssembler.class);

    private record Pending(Map<String, TunnelWindow> byTunnel, long lastArrival) {
    }

    private final TriageEngine engine;
    private final long quietMs;
    private final TreeMap<Long, Pending> pending = new TreeMap<>();
    private final Deque<Views.WindowReport> recent = new ArrayDeque<>();
    private long lastProcessed = Long.MIN_VALUE;
    private final Counter received;
    private final Counter late;
    private final Timer diagnose;

    public WindowAssembler(TriageEngine engine, NetTriageProperties props, MeterRegistry meters) {
        this.engine = engine;
        this.quietMs = props.windowQuiet().toMillis();
        this.received = meters.counter("nettriage.records.received");
        this.late = meters.counter("nettriage.records.late");
        this.diagnose = meters.timer("nettriage.window.diagnose");
        meters.gauge("nettriage.incidents.open", engine, e -> e.incidents(false).size());
    }

    public synchronized void accept(TunnelWindow w) {
        received.increment();
        if (w.windowEnd() <= lastProcessed) {
            late.increment();
            return;
        }
        Pending p = pending.get(w.windowEnd());
        Map<String, TunnelWindow> m = p == null ? new java.util.HashMap<>() : p.byTunnel();
        m.put(w.tunnel().key(), w);
        pending.put(w.windowEnd(), new Pending(m, System.currentTimeMillis()));
    }

    /** Diagnoses every window that has gone quiet, oldest first. */
    @Scheduled(fixedDelay = 250)
    public void flush() {
        long now = System.currentTimeMillis();
        closeWhile(e -> now - e.lastArrival() >= quietMs);
    }

    /** Diagnoses every pending window up to and including {@code windowEnd} right away. */
    public void flushUpTo(long windowEnd) {
        closeUpTo(windowEnd);
    }

    private synchronized void closeUpTo(long windowEnd) {
        while (!pending.isEmpty() && pending.firstKey() <= windowEnd) {
            process(pending.pollFirstEntry());
        }
    }

    private synchronized void closeWhile(java.util.function.Predicate<Pending> ready) {
        while (!pending.isEmpty() && ready.test(pending.firstEntry().getValue())) {
            process(pending.pollFirstEntry());
        }
    }

    private void process(Map.Entry<Long, Pending> e) {
        long t0 = System.nanoTime();
        Views.WindowReport r = engine.processWindow(e.getKey(), e.getValue().byTunnel().values());
        diagnose.record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        lastProcessed = e.getKey();
        recent.addFirst(r);
        if (recent.size() > 60) {
            recent.removeLast();
        }
        if (!r.openedIncidents().isEmpty()) {
            log.info("window {}: opened {} ({} of {} tunnels degraded, causes {})", e.getKey(), r.openedIncidents(),
                    r.degraded(), r.observed(), r.causes());
        }
    }

    public synchronized List<Views.WindowReport> recent() {
        return new ArrayList<>(recent);
    }

    public synchronized Map<String, Object> stats() {
        return Map.of("pendingWindows", pending.size(), "recordsReceived", (long) received.count(),
                "lateRecords", (long) late.count(), "lastWindowDiagnosed", lastProcessed == Long.MIN_VALUE ? 0 : lastProcessed);
    }
}
