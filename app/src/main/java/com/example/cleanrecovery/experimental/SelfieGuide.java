package com.example.cleanrecovery.experimental;

/** Geometry and consecutive-stability gate, independent of the camera/model runtime. */
public final class SelfieGuide {
    private long stableSince=-1,lastSample=-1;
    private float lastX,lastY,lastSize;
    public String evaluate(int count,float left,float top,float right,float bottom,float yaw,float pitch,float roll,
                           Float leftEye,Float rightEye,boolean moving,long now) {
        String reason=null;
        float x=(left+right)/2,y=(top+bottom)/2,size=right-left;
        if(count==0) reason="未找到人脸，请将后置镜头朝向自己";
        else if(count!=1) reason="画面中有多张脸，请只保留一人";
        else if(!Float.isFinite(x+y+size+yaw+pitch+roll)) reason="正在重新识别人脸";
        else if(left<.04f || right>.96f || top<.04f || bottom>.96f) reason="脸部靠近边缘，请把手机拿远一点";
        else if(size<.25f) reason="请把手机靠近一点";
        else if(size>.58f || bottom-top>.70f) reason="请把手机拿远一点";
        else if(x<.40f) reason="请把手机向你的右侧移动一点";
        else if(x>.60f) reason="请把手机向你的左侧移动一点";
        else if(y<.30f) reason="请把手机抬高一点";
        else if(y>.55f) reason="请把手机放低一点";
        else if(Math.abs(yaw)>20 || Math.abs(pitch)>20 || Math.abs(roll)>15) reason="请正对镜头，保持头部端正";
        else if((leftEye!=null && leftEye<.4f)||(rightEye!=null && rightEye<.4f)) reason="请睁眼看向镜头";
        else if(moving) reason="请稳住手机";
        else if(lastSample>=0 && now-lastSample<700 && (Math.abs(x-lastX)>.025f || Math.abs(y-lastY)>.025f || Math.abs(size-lastSize)>.025f)) reason="请稳住手机";
        if(lastSample<0 || now-lastSample>700) stableSince=-1;
        lastSample=now; lastX=x; lastY=y; lastSize=size;
        if(reason!=null) { stableSince=-1; return reason; }
        if(stableSince<0) stableSince=now;
        return now-stableSince>=1000 ? "构图就绪" : "保持姿态";
    }
    public void reset() { stableSince=-1; lastSample=-1; }
}
