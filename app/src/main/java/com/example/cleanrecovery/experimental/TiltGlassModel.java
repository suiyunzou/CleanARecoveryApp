package com.example.cleanrecovery.experimental;

/** Gravity relative to a reference posture, expressed in current screen coordinates. */
public final class TiltGlassModel {
    public static final class Tilt {
        public final float x, y;
        public Tilt(float x, float y) { this.x=x; this.y=y; }
        public float amount() { return Math.min(1, (float)Math.hypot(x,y)); }
    }
    private float smoothedX=1, smoothedY, smoothedAmount;
    private long lastMs = -1;

    /** Project the calibrated world-space screen normal into current device coordinates. */
    public static float[] relativeNormal(float[] matrix,float[] normal) {
        float[] result=new float[3];
        for(int i=0;i<3;i++) result[i]=(matrix[i]*normal[0]+matrix[3+i]*normal[1]+matrix[6+i]*normal[2])*9.81f;
        return result;
    }
    public static Tilt direction(float[] gravity, float[] reference) {
        double g=length(gravity), r=length(reference);
        if (!Double.isFinite(g+r) || g<1 || r<1) return new Tilt(0,0);
        double nx=reference[0]/r, ny=reference[1]/r, nz=reference[2]/r;
        // Project screen-right onto the plane perpendicular to the calibrated gravity.
        double rx=1-nx*nx, ry=-nx*ny, rz=-nx*nz;
        double rightLength=Math.sqrt(rx*rx+ry*ry+rz*rz);
        if(rightLength<.01) { rx=-nz*nx; ry=-nz*ny; rz=1-nz*nz; rightLength=Math.sqrt(rx*rx+ry*ry+rz*rz); }
        rx/=rightLength; ry/=rightLength; rz/=rightLength;
        double fx=ny*rz-nz*ry, fy=nz*rx-nx*rz, fz=nx*ry-ny*rx;
        double center=(gravity[0]*nx+gravity[1]*ny+gravity[2]*nz)/g;
        double horizontal=(gravity[0]*rx+gravity[1]*ry+gravity[2]*rz)/g;
        double vertical=(gravity[0]*fx+gravity[1]*fy+gravity[2]*fz)/g;
        // Use the actual vector angle; combining two Euler angles overcounts diagonal tilt.
        double degrees=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,center))));
        if(degrees<=3) return new Tilt(0,0);
        double amount=Math.min(1,(degrees-3)/87);
        double tangent=Math.hypot(horizontal,vertical);
        // Beyond 90 degrees retain full blur, including exactly face-down (direction undefined).
        if(tangent<1e-6) return new Tilt((float)amount,0);
        return new Tilt((float)(-horizontal/tangent*amount),(float)(vertical/tangent*amount));
    }
    public Tilt smooth(Tilt target,long ms) {
        long elapsed=lastMs<0 ? 33 : Math.max(0,Math.min(100,ms-lastMs));
        lastMs=ms;
        float blend=(float)(1-Math.exp(-elapsed/140.0));
        float amount=target.amount();
        smoothedAmount+=(amount-smoothedAmount)*blend;
        if(Math.abs(amount-smoothedAmount)<.001f) smoothedAmount=amount;
        // Smooth coverage independently so changing direction at 90 degrees cannot reveal content.
        if(amount>.001f) {
            smoothedX+=(target.x/amount-smoothedX)*blend;
            smoothedY+=(target.y/amount-smoothedY)*blend;
        }
        float norm=(float)Math.hypot(smoothedX,smoothedY);
        if(norm<.001f) { smoothedX=amount>0?target.x/amount:1; smoothedY=amount>0?target.y/amount:0; norm=1; }
        return new Tilt(smoothedX/norm*smoothedAmount,smoothedY/norm*smoothedAmount);
    }
    public void reset() { smoothedX=1; smoothedY=0; smoothedAmount=0; lastMs=-1; }
    private static double length(float[] v) { return Math.sqrt(v[0]*(double)v[0]+v[1]*(double)v[1]+v[2]*(double)v[2]); }
}
