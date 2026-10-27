package io.nettriage.core.detect;

/** Which measurements moved. Stored as bit flags on a reading. */
public final class Symptom {
    public static final int LOSS = 1;
    public static final int LATENCY = 2;
    public static final int JITTER = 4;
    public static final int BLACKOUT = 8;

    private Symptom() {
    }

    public static String describe(int flags) {
        if ((flags & BLACKOUT) != 0) {
            return "blackout";
        }
        StringBuilder sb = new StringBuilder();
        if ((flags & LOSS) != 0) {
            sb.append("loss");
        }
        if ((flags & LATENCY) != 0) {
            sb.append(sb.isEmpty() ? "" : "+").append("latency");
        }
        if ((flags & JITTER) != 0) {
            sb.append(sb.isEmpty() ? "" : "+").append("jitter");
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }
}
