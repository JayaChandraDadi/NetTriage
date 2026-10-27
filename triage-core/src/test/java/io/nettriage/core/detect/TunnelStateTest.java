package io.nettriage.core.detect;

import io.nettriage.core.model.Tunnel;
import io.nettriage.core.model.TunnelWindow;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TunnelStateTest {

    private static final Tunnel T = new Tunnel("a:lte->h:lte", 1, "a", "us-west", "lte", "h");
    private final DetectorSettings s = DetectorSettings.defaults();
    private final Random rnd = new Random(7);
    private long t;

    private TunnelWindow window(double lossProb, double latency, double jitter) {
        int lost = 0;
        for (int i = 0; i < 100; i++) {
            if (rnd.nextDouble() < lossProb) {
                lost++;
            }
        }
        t += 10_000;
        double lat = latency + rnd.nextGaussian() * 4;
        double jit = jitter + rnd.nextGaussian() * 2;
        return lost == 100 ? new TunnelWindow(T, t, 100, 100, null, null, true)
                : new TunnelWindow(T, t, 100, lost, lat, jit, true);
    }

    @Test
    void noisyButNormalTunnelIsNeverDegraded() {
        TunnelState st = new TunnelState();
        int degraded = 0;
        for (int i = 0; i < 2000; i++) {
            if (st.observe(window(0.006, 60, 12), s) == Health.DEGRADED) {
                degraded++;
            }
        }
        assertThat(degraded).isZero();
        assertThat(st.baselineLatencyMs()).isBetween(57.0, 63.0);
    }

    @Test
    void latencyStepIsDegradedOnTheSecondWindowAndBaselineDoesNotLearnIt() {
        TunnelState st = new TunnelState();
        for (int i = 0; i < 50; i++) {
            st.observe(window(0.002, 30, 4), s);
        }
        long faultStart = t + 10_000;
        assertThat(st.observe(window(0.002, 100, 4), s)).isNotEqualTo(Health.DEGRADED);
        assertThat(st.observe(window(0.002, 100, 4), s)).isEqualTo(Health.DEGRADED);
        assertThat(st.degradedSince()).isEqualTo(faultStart);
        assertThat(st.flags() & Symptom.LATENCY).isNotZero();
        for (int i = 0; i < 100; i++) {
            assertThat(st.observe(window(0.002, 100, 4), s)).isEqualTo(Health.DEGRADED);
        }
        assertThat(st.baselineLatencyMs()).isLessThan(35);
    }

    @Test
    void blackoutIsFlaggedAndRecoveryClearsIt() {
        TunnelState st = new TunnelState();
        for (int i = 0; i < 20; i++) {
            st.observe(window(0.002, 30, 4), s);
        }
        st.observe(window(1.0, 0, 0), s);
        assertThat(st.observe(window(1.0, 0, 0), s)).isEqualTo(Health.DEGRADED);
        assertThat(st.flags() & Symptom.BLACKOUT).isNotZero();
        st.observe(window(0.002, 30, 4), s);
        st.observe(window(0.002, 30, 4), s);
        assertThat(st.observe(window(0.002, 30, 4), s)).isEqualTo(Health.HEALTHY);
        assertThat(st.degradedSince()).isEqualTo(-1);
    }

    @Test
    void tunnelIsNotJudgedDuringWarmup() {
        TunnelState st = new TunnelState();
        for (int i = 0; i < s.warmupWindows() - 1; i++) {
            assertThat(st.observe(window(0.002, 30, 4), s)).isEqualTo(Health.UNKNOWN);
        }
    }
}
