package com.example.cleanrecovery.experimental;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.example.cleanrecovery.experimental.AcousticDetector.State.*;
public class WaveTriggerTest {
    private long now;
    private boolean sample(WaveTrigger gate,AcousticDetector.State state) { now+=100; return gate.accept(state,0,true,now); }
    private void cycle(WaveTrigger gate) {
        assertFalse(sample(gate,TOWARD)); assertFalse(sample(gate,IDLE)); assertFalse(sample(gate,AWAY)); assertFalse(sample(gate,IDLE));
    }
    private boolean release(WaveTrigger gate) { boolean shot=false; for(int i=0;i<5;i++) shot|=sample(gate,IDLE); return shot; }
    @Test public void threeCompleteWavesThenReleaseProduceOneShot() {
        WaveTrigger gate=new WaveTrigger(3); cycle(gate); cycle(gate); assertEquals(2,gate.count()); cycle(gate);
        assertTrue(release(gate)); assertEquals(0,gate.count()); assertFalse(release(gate));
    }
    @Test public void twoWaveOptionIsIndependent() { WaveTrigger gate=new WaveTrigger(2); cycle(gate); cycle(gate); assertTrue(release(gate)); }
    @Test public void sameDirectionOrWithdrawalAloneCannotTrigger() {
        WaveTrigger gate=new WaveTrigger(2); for(int i=0;i<15;i++) assertFalse(sample(gate,TOWARD));
        assertEquals(0,gate.count()); gate.inhibit(now,0);
        for(int i=0;i<8;i++) assertFalse(sample(gate,AWAY)); assertEquals(0,gate.count());
    }
    @Test public void pocketOrMotionDuringReleaseCancelsEntireSequence() {
        WaveTrigger gate=new WaveTrigger(2); cycle(gate); cycle(gate);
        assertFalse(gate.accept(IDLE,0,false,now+100)); assertEquals(0,gate.count()); assertFalse(release(gate));
    }
    @Test public void weakNoisyAndCalibrationClearPartialWaves() {
        for(AcousticDetector.State state:new AcousticDetector.State[]{WEAK,NOISY,CALIBRATING}) {
            WaveTrigger gate=new WaveTrigger(3); cycle(gate); sample(gate,state); assertEquals(0,gate.count());
        }
    }
    @Test public void staleAudioCannotFinishSequence() {
        WaveTrigger gate=new WaveTrigger(2); cycle(gate); cycle(gate); now+=600;
        assertFalse(release(gate)); assertEquals(0,gate.count());
    }
    @Test public void continuedHandMovementAfterLastWaveCancelsShot() {
        WaveTrigger gate=new WaveTrigger(2); cycle(gate); cycle(gate);
        assertFalse(gate.accept(IDLE,.2f,true,now+100)); assertEquals(0,gate.count());
    }
    @Test public void cooldownRejectsAdditionalWaves() {
        WaveTrigger gate=new WaveTrigger(2); cycle(gate); cycle(gate); assertTrue(release(gate));
        cycle(gate); cycle(gate); assertFalse(release(gate));
    }
    @Test public void excessivelySlowOrFastHalfCycleDoesNotCount() {
        WaveTrigger gate=new WaveTrigger(2); sample(gate,TOWARD); sample(gate,AWAY); assertEquals(0,gate.count());
        sample(gate,TOWARD); for(int i=0;i<15;i++) sample(gate,IDLE); sample(gate,AWAY); assertEquals(0,gate.count());
    }
}
