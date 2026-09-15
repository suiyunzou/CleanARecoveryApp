package com.example.cleanrecovery.experimental;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Random;

public class ExperimentalDetectorsTest {
    @Test public void stationaryArmsWithoutMovement() {
        MotionDetector d=new MotionDetector(); int armed=0;
        for(int t=0;t<10000;t+=20) {
            MotionDetector.Event e=d.sample(t,.01*Math.sin(t),0,9.81);
            if(e==MotionDetector.Event.ARMED) armed++;
            assertNotEquals(MotionDetector.Event.MOVED,e);
        }
        assertEquals(1,armed);
    }
    @Test public void movingDuringSetupDelaysArming() {
        MotionDetector d=new MotionDetector();
        for(int t=0;t<5000;t+=20) assertNotEquals(MotionDetector.Event.ARMED,d.sample(t,3*Math.sin(t/50.0),0,9.81));
    }
    @Test public void movementSettlesAndCanTriggerAgain() {
        MotionDetector d=new MotionDetector(); int moved=0,settled=0;
        for(int t=0;t<16000;t+=20) {
            double a=(t>=4000&&t<5000)||(t>=10000&&t<11000) ? 3*Math.sin(t/50.0) : 0;
            MotionDetector.Event e=d.sample(t,a,0,9.81);
            if(e==MotionDetector.Event.MOVED) moved++;
            if(e==MotionDetector.Event.SETTLED) { settled++; assertTrue(d.durationMs>500); }
        }
        assertEquals(2,moved); assertEquals(2,settled);
    }
    @Test public void singleImpulseDoesNotReportPickup() {
        MotionDetector d=new MotionDetector();
        for(int t=0;t<7000;t+=20) assertNotEquals(MotionDetector.Event.MOVED,d.sample(t,t==4000?2:0,0,9.81));
    }
    private short[] audio(double reflectionFrequency,double reflectionAmplitude) {
        short[] data=new short[AcousticDetector.SIZE];
        for(int i=0;i<data.length;i++) data[i]=(short)(8000*Math.sin(2*Math.PI*19000*i/48000)
                +reflectionAmplitude*Math.sin(2*Math.PI*reflectionFrequency*i/48000));
        return data;
    }
    private AcousticDetector calibrated() {
        AcousticDetector d=new AcousticDetector();
        for(int i=0;i<24;i++) assertEquals(AcousticDetector.State.CALIBRATING,d.accept(audio(0,0),i*86).state);
        return d;
    }
    @Test public void pureCarrierNeverTriggers() {
        AcousticDetector d=calibrated();
        for(int i=0;i<40;i++) assertEquals(AcousticDetector.State.IDLE,d.accept(audio(0,0),2200+i*86).state);
    }
    @Test public void silenceIsWeakNotGesture() {
        assertEquals(AcousticDetector.State.WEAK,new AcousticDetector().accept(new short[4096],0).state);
    }
    @Test public void positiveAndNegativeDopplerHaveOppositeDirections() {
        AcousticDetector d=calibrated();
        d.accept(audio(19120,1800),2500);
        assertEquals(AcousticDetector.State.TOWARD,d.accept(audio(19120,1800),2600).state);
        d.accept(audio(18880,1800),3500);
        assertEquals(AcousticDetector.State.AWAY,d.accept(audio(18880,1800),3600).state);
    }
    @Test public void oneFrameSpikeAndCooldownDoNotRepeatGesture() {
        AcousticDetector d=calibrated();
        assertEquals(AcousticDetector.State.IDLE,d.accept(audio(19120,1800),2500).state);
        assertEquals(AcousticDetector.State.IDLE,d.accept(audio(0,0),2600).state);
        d.accept(audio(19120,1800),2700);
        assertEquals(AcousticDetector.State.TOWARD,d.accept(audio(19120,1800),2800).state);
        for(int i=0;i<4;i++) assertEquals(AcousticDetector.State.IDLE,d.accept(audio(19120,1800),2900+i*86).state);
    }
    @Test public void clippingIsRejected() {
        short[] samples=new short[4096]; java.util.Arrays.fill(samples,(short)32767);
        assertEquals(AcousticDetector.State.NOISY,new AcousticDetector().accept(samples,0).state);
    }
    @Test public void broadbandNoiseDoesNotGenerateGesture() {
        AcousticDetector d=calibrated(); Random random=new Random(4);
        for(int frame=0;frame<30;frame++) {
            short[] samples=new short[4096]; for(int i=0;i<samples.length;i++) samples[i]=(short)(random.nextInt(16000)-8000);
            AcousticDetector.State state=d.accept(samples,3000+frame*86).state;
            assertNotEquals(AcousticDetector.State.TOWARD,state); assertNotEquals(AcousticDetector.State.AWAY,state);
        }
    }
}
