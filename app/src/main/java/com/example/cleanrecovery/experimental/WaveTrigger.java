package com.example.cleanrecovery.experimental;

/** Counts complete approach/withdraw cycles, then requires fresh quiet frames before one shot. */
public final class WaveTrigger {
    private final int required;
    private int count;
    private long approach=-1,lastCycle=-1,quiet=-1,lastFrame=-1,blockedUntil;
    public WaveTrigger(int required) { this.required=required==2?2:3; }
    public int count() { return count; }
    public void inhibit(long now,long hold) { reset(); blockedUntil=Math.max(blockedUntil,now+hold); }
    private void reset() { count=0; approach=-1; lastCycle=-1; quiet=-1; }
    public boolean accept(AcousticDetector.State state,float energy,boolean safe,long now) {
        if(!safe) { inhibit(now,1500); lastFrame=now; return false; }
        if(lastFrame>=0 && (now<lastFrame || now-lastFrame>500)) reset();
        lastFrame=now;
        if(now<blockedUntil) { reset(); return false; }
        if(state==AcousticDetector.State.WEAK || state==AcousticDetector.State.NOISY || state==AcousticDetector.State.CALIBRATING) { reset(); return false; }
        if((approach>=0 && now-approach>1400) || (lastCycle>=0 && now-lastCycle>1800)) reset();
        if(count==required) {
            if(state!=AcousticDetector.State.IDLE || energy>.06f) { reset(); return false; }
            if(quiet<0) quiet=now;
            if(now-quiet>=450) { inhibit(now,3000); return true; }
            return false;
        }
        if(state==AcousticDetector.State.TOWARD) {
            if(approach<0) approach=now;
        } else if(state==AcousticDetector.State.AWAY) {
            if(approach>=0 && now-approach>=120 && now-approach<=1400) { count++; lastCycle=now; }
            else reset();
            approach=-1;
        }
        return false;
    }
}
