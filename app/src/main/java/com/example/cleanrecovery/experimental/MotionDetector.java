package com.example.cleanrecovery.experimental;

/** Timestamp-driven detector; acceleration includes gravity. No inferred distance or identity. */
public final class MotionDetector {
    public enum Event { NONE, ARMED, MOVED, SETTLED }
    private double x, y, z;
    private boolean initialized, armed, moving;
    private long stableSince, candidateSince = -1, lastMotion, started;
    public double intensity;
    public long durationMs;

    public Event sample(long ms, double ax, double ay, double az) {
        if (!Double.isFinite(ax + ay + az)) return Event.NONE;
        if (!initialized) {
            initialized = true; x = ax; y = ay; z = az; stableSince = ms;
            return Event.NONE;
        }
        intensity = Math.sqrt(square(ax-x) + square(ay-y) + square(az-z));
        // Follow the stationary gravity vector slowly; retain sensitivity to rotation.
        x += .06*(ax-x); y += .06*(ay-y); z += .06*(az-z);
        boolean active = intensity > .65;
        if (!armed) {
            if (active) stableSince = ms;
            if (ms-stableSince >= 3000) { armed = true; return Event.ARMED; }
            return Event.NONE;
        }
        if (active) {
            lastMotion = ms;
            if (candidateSince < 0) candidateSince = ms;
            if (!moving && ms-candidateSince >= 120) {
                moving = true; started = candidateSince; return Event.MOVED;
            }
        } else {
            candidateSince = -1;
            if (moving && ms-lastMotion >= 2000) {
                moving = false; durationMs = Math.max(0, lastMotion-started);
                return Event.SETTLED;
            }
        }
        return Event.NONE;
    }
    private static double square(double v) { return v*v; }
}
