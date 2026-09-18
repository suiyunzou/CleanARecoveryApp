package com.example.cleanrecovery.experimental;

import org.junit.Test;
import static org.junit.Assert.*;

public class TiltGlassModelTest {
    private final float[] flat={0,0,9.81f};
    @Test public void levelAndSmallJitterRemainClear() {
        assertEquals(0,TiltGlassModel.direction(flat,flat).amount(),0);
        assertEquals(0,TiltGlassModel.direction(new float[]{.12f,-.14f,9.81f},flat).amount(),0);
    }
    @Test public void fourEdgesHaveConsistentScreenDirections() {
        assertTrue(TiltGlassModel.direction(new float[]{4,0,8.5f},flat).x<0);
        assertTrue(TiltGlassModel.direction(new float[]{-4,0,8.5f},flat).x>0);
        assertTrue(TiltGlassModel.direction(new float[]{0,-4,8.5f},flat).y<0);
        assertTrue(TiltGlassModel.direction(new float[]{0,4,8.5f},flat).y>0);
    }
    @Test public void diagonalAndIntensityAreContinuousAndBounded() {
        TiltGlassModel.Tilt a=TiltGlassModel.direction(new float[]{-2,2,9},flat);
        TiltGlassModel.Tilt b=TiltGlassModel.direction(new float[]{-5,5,6},flat);
        assertTrue(a.x>0 && a.y>0); assertEquals(a.x,a.y,.0001f);
        assertTrue(b.amount()>a.amount()); assertTrue(b.amount()<=1);
    }
    @Test public void customUprightReferenceDistinguishesFrontAndBack() {
        float[] upright={0,9.81f,0};
        assertEquals(0,TiltGlassModel.direction(upright,upright).amount(),0);
        assertTrue(TiltGlassModel.direction(new float[]{0,9,3},upright).y<0);
        assertTrue(TiltGlassModel.direction(new float[]{0,9,-3},upright).y>0);
    }
    @Test public void sidewaysCalibrationDoesNotProduceNaN() {
        assertEquals(0,TiltGlassModel.direction(new float[]{9.81f,0,0},new float[]{9.81f,0,0}).amount(),.0001f);
    }
    @Test public void invalidGravityStaysClear() {
        assertEquals(0,TiltGlassModel.direction(new float[]{0,0,0},flat).amount(),0);
        assertEquals(0,TiltGlassModel.direction(new float[]{Float.NaN,0,9},flat).amount(),0);
    }
    @Test public void uprightYawUsesScreenNormalEvenWhenGravityIsUnchanged() {
        float[] upright={1,0,0,0,0,-1,0,1,0};
        float[] turned={0,0,1,1,0,0,0,1,0};
        float[] normal={0,-1,0};
        assertEquals(0,TiltGlassModel.direction(TiltGlassModel.relativeNormal(upright,normal),flat).amount(),.0001f);
        assertEquals(1,TiltGlassModel.direction(TiltGlassModel.relativeNormal(turned,normal),flat).amount(),.0001f);
        for(int i=6;i<9;i++) assertEquals(upright[i],turned[i],0);
    }
    private float[] posture(double degrees,double yaw) {
        double a=Math.toRadians(degrees),b=Math.toRadians(yaw);
        float c=(float)Math.cos(a),s=(float)Math.sin(a),u=(float)Math.cos(b),v=(float)Math.sin(b);
        return new float[]{u,-v*c,v*s,v,u*c,-u*s,0,s,c};
    }
    @Test public void selectedInclinationIsClearRegardlessOfInitialCompassHeading() {
        for(int angle:new int[]{0,15,45,60,90}) for(int yaw:new int[]{0,90,180,270}) {
            float[] matrix=posture(angle,yaw);
            float[] reference=TiltGlassModel.readingNormal(matrix,new float[]{0,1,0},angle);
            assertEquals(0,TiltGlassModel.direction(TiltGlassModel.relativeNormal(matrix,reference),flat).amount(),.001f);
        }
    }
    @Test public void fullBlurIsRelativeToSelectedAngleRatherThanHorizontal() {
        float[] reference=TiltGlassModel.readingNormal(posture(45,0),new float[]{0,1,0},45);
        assertEquals(42/87f,TiltGlassModel.direction(TiltGlassModel.relativeNormal(posture(90,0),reference),flat).amount(),.001f);
        assertEquals(1,TiltGlassModel.direction(TiltGlassModel.relativeNormal(posture(135,0),reference),flat).amount(),.001f);
        assertEquals(0,TiltGlassModel.direction(TiltGlassModel.relativeNormal(posture(45,0),reference),flat).amount(),.001f);
    }
    @Test public void fallbackReferenceMatchesReadingAngleAndHorizontalEndpoint() {
        assertArrayEquals(flat,TiltGlassModel.readingGravity(0),.001f);
        assertArrayEquals(new float[]{0,9.81f,0},TiltGlassModel.readingGravity(90),.001f);
        assertEquals(0,TiltGlassModel.direction(gravityAt(45,90),TiltGlassModel.readingGravity(45)).amount(),.001f);
    }
    private float[] gravityAt(double degrees,double azimuth) {
        double a=Math.toRadians(degrees),b=Math.toRadians(azimuth);
        return new float[]{(float)(9.81*Math.sin(a)*Math.cos(b)),(float)(9.81*Math.sin(a)*Math.sin(b)),(float)(9.81*Math.cos(a))};
    }
    @Test public void ninetyDegreesIsFullForEveryDirectionAndBeyond() {
        for(double azimuth:new double[]{0,45,90,135,180,225,270,315}) {
            for(double angle:new double[]{90,91,120,179,180})
                assertEquals("angle="+angle+", direction="+azimuth,1,TiltGlassModel.direction(gravityAt(angle,azimuth),flat).amount(),.0001f);
        }
    }
    @Test public void coverageGrowsUntilNinetyNotFortyDegrees() {
        float previous=0;
        for(int angle=4;angle<90;angle++) {
            float amount=TiltGlassModel.direction(gravityAt(angle,0),flat).amount();
            assertTrue(amount>previous); assertTrue(amount<1); previous=amount;
        }
        assertEquals((40-3)/87f,TiltGlassModel.direction(gravityAt(40,0),flat).amount(),.0001f);
    }
    @Test public void diagonalUsesPhysicalAngleAndMatchesCardinalCoverage() {
        for(int angle:new int[]{15,30,45,60,75,89}) {
            float straight=TiltGlassModel.direction(gravityAt(angle,0),flat).amount();
            assertEquals(straight,TiltGlassModel.direction(gravityAt(angle,45),flat).amount(),.0001f);
        }
    }
    @Test public void changingDirectionWhileFullyBlurredDoesNotRevealPixels() {
        TiltGlassModel model=new TiltGlassModel();
        for(int t=0;t<2000;t+=33) model.smooth(new TiltGlassModel.Tilt(1,0),t);
        for(int t=2000;t<4000;t+=33)
            assertEquals(1,model.smooth(new TiltGlassModel.Tilt(-1,0),t).amount(),.0001f);
    }
    @Test public void smoothingRampsUpAndReturnsToClear() {
        TiltGlassModel model=new TiltGlassModel();
        float first=model.smooth(new TiltGlassModel.Tilt(1,0),0).x;
        assertTrue(first>0 && first<.5f);
        float previous=first;
        for(int t=33;t<1000;t+=33) {
            float next=model.smooth(new TiltGlassModel.Tilt(1,0),t).x;
            assertTrue(next>=previous); previous=next;
        }
        assertTrue(previous>.99f);
        TiltGlassModel.Tilt last=null;
        for(int t=1000;t<2600;t+=33) last=model.smooth(new TiltGlassModel.Tilt(0,0),t);
        assertEquals(0,last.amount(),.002f);
    }
}
