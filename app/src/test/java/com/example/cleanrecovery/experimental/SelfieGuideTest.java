package com.example.cleanrecovery.experimental;
import org.junit.Test;
import static org.junit.Assert.*;
public class SelfieGuideTest {
    private String sample(SelfieGuide g,long time,boolean moving) { return g.evaluate(1,.32f,.22f,.68f,.65f,0,0,0,.9f,.9f,moving,time); }
    @Test public void requiresConsecutiveStableFrames() {
        SelfieGuide g=new SelfieGuide(); assertEquals("保持姿态",sample(g,0,false));
        for(int t=200;t<1000;t+=200) assertEquals("保持姿态",sample(g,t,false));
        assertEquals("构图就绪",sample(g,1000,false));
        assertEquals("请稳住手机",sample(g,1100,true));
        assertEquals("保持姿态",sample(g,1200,false));
    }
    @Test public void missingOrMultipleFacesNeverReady() {
        SelfieGuide g=new SelfieGuide();
        assertTrue(g.evaluate(0,0,0,0,0,0,0,0,null,null,false,0).startsWith("未找到"));
        assertTrue(g.evaluate(2,.3f,.2f,.7f,.6f,0,0,0,null,null,false,2000).contains("多张"));
    }
    @Test public void lostFramesResetReadiness() {
        SelfieGuide g=new SelfieGuide(); for(int t=0;t<=1000;t+=200) sample(g,t,false);
        assertEquals("保持姿态",sample(g,1900,false));
    }
    @Test public void clippedFaceClosedEyesAndPoseBlockCapture() {
        SelfieGuide g=new SelfieGuide();
        assertTrue(g.evaluate(1,0,.2f,.5f,.6f,0,0,0,null,null,false,0).contains("边缘"));
        assertTrue(g.evaluate(1,.3f,.2f,.7f,.6f,30,0,0,null,null,false,100).contains("正对"));
        assertTrue(g.evaluate(1,.3f,.2f,.7f,.6f,0,0,0,.1f,.9f,false,200).contains("睁眼"));
    }
    @Test public void directionIsExpressedFromSubjectsPerspective() {
        SelfieGuide g=new SelfieGuide();
        assertTrue(g.evaluate(1,.05f,.2f,.4f,.6f,0,0,0,null,null,false,0).contains("你的右侧"));
        assertTrue(g.evaluate(1,.6f,.2f,.95f,.6f,0,0,0,null,null,false,100).contains("你的左侧"));
    }
}
