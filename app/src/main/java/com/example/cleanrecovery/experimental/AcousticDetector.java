package com.example.cleanrecovery.experimental;

/** Experimental single-channel Doppler detector, not a distance/left-right tracker. */
public final class AcousticDetector {
    public static final int RATE = 48000, SIZE = 4096, CARRIER = 19000;
    public enum State { CALIBRATING, WEAK, NOISY, IDLE, TOWARD, AWAY }
    public static final class Result {
        public final State state;
        public final float energy;
        public final double carrier;
        Result(State state, float energy, double carrier) { this.state=state; this.energy=energy; this.carrier=carrier; }
    }
    private int frames, consecutive, direction;
    private final long gestureInterval;
    private double baselineLow, baselineHigh;
    private long lastGesture = -1000;
    private final double[] window = new double[SIZE];
    public AcousticDetector() { this(700); }
    public AcousticDetector(long interval) {
        gestureInterval=interval;
        for (int i=0; i<SIZE; i++) window[i] = .5-.5*Math.cos(2*Math.PI*i/(SIZE-1));
    }
    public Result accept(short[] samples, long ms) {
        if (samples.length != SIZE) throw new IllegalArgumentException("Expected one complete audio frame");
        double carrier = power(samples, CARRIER), low=0, high=0;
        int clipped = 0;
        for (short sample : samples) if (Math.abs((int)sample) > 32000) clipped++;
        for (int offset=47; offset<=281; offset+=23) {
            low += power(samples, CARRIER-offset);
            high += power(samples, CARRIER+offset);
        }
        if (clipped > SIZE/100 || low+high > carrier*1.5) {
            consecutive = 0; return new Result(State.NOISY, 0, carrier);
        }
        if (carrier < 1e-7) { consecutive=0; return new Result(State.WEAK, 0, carrier); }
        low /= carrier; high /= carrier;
        if (frames < 24) {
            baselineLow += low/24; baselineHigh += high/24; frames++;
            return new Result(State.CALIBRATING, 0, carrier);
        }
        double l = Math.max(0, low-baselineLow), h = Math.max(0, high-baselineHigh);
        float strength = (float)Math.min(1, Math.max(l,h)*8);
        int candidate = h > Math.max(.006, baselineHigh*3) && h > l*2 ? 1
                : l > Math.max(.006, baselineLow*3) && l > h*2 ? -1 : 0;
        if (candidate == 0) {
            consecutive=0;
            baselineLow = baselineLow*.995+low*.005;
            baselineHigh = baselineHigh*.995+high*.005;
        } else {
            consecutive = candidate == direction ? consecutive+1 : 1;
            direction = candidate;
            if (consecutive >= 2 && ms-lastGesture >= gestureInterval) {
                lastGesture=ms; consecutive=0;
                return new Result(candidate>0 ? State.TOWARD : State.AWAY, strength, carrier);
            }
        }
        return new Result(State.IDLE, strength, carrier);
    }
    private double power(short[] data, double frequency) {
        double coefficient = 2*Math.cos(2*Math.PI*frequency/RATE), a=0, b=0;
        for (int i=0; i<SIZE; i++) {
            double next = data[i]/32768.0*window[i]+coefficient*a-b;
            b=a; a=next;
        }
        return Math.max(0, a*a+b*b-coefficient*a*b)/(SIZE*(double)SIZE);
    }
}
