package com.example.cleanrecovery.experimental;

import android.graphics.*;
import android.view.View;
import androidx.annotation.RequiresApi;

/** GPU composition of three Gaussian scales with directional, continuous alpha masks. */
@RequiresApi(31)
final class TiltGlassRenderer {
    private int width=-1,height=-1,xKey=Integer.MIN_VALUE,yKey,strengthKey,amountKey;
    private boolean nightKey;

    void apply(View view, TiltGlassModel.Tilt tilt, int strength, boolean night) {
        int x=Math.round(tilt.x*50), y=Math.round(tilt.y*50);
        float amount=tilt.amount();
        int progress=Math.round(amount*1000);
        if(width==view.getWidth() && height==view.getHeight() && x==xKey && y==yKey
                && progress==amountKey && strength==strengthKey && night==nightKey) return;
        width=view.getWidth(); height=view.getHeight(); xKey=x; yKey=y; strengthKey=strength; nightKey=night; amountKey=progress;
        if(amount<.02f || width==0 || height==0) { view.setRenderEffect(null); return; }
        float norm=(float)Math.hypot(x,y), dx=x/norm, dy=y/norm;
        float extent=(Math.abs(dx)*width+Math.abs(dy)*height)/2;
        float density=view.getResources().getDisplayMetrics().density;
        float maxRadius=density*(4+strength*.16f)*(.30f+.70f*amount);
        int tint=Color.argb(Math.round(10*amount),night?25:255,night?28:255,night?38:255);
        if(amount>=.9999f) {
            // A single full-surface pass: no clear original layer leaks through at 90 degrees.
            view.setRenderEffect(RenderEffect.createColorFilterEffect(new PorterDuffColorFilter(tint,PorterDuff.Mode.SRC_ATOP),
                    RenderEffect.createBlurEffect(maxRadius,maxRadius,Shader.TileMode.CLAMP)));
            return;
        }
        RenderEffect original=RenderEffect.createOffsetEffect(0,0);
        RenderEffect result=original;
        float[] feathers={.9f,.65f,.4f};
        float[] radii={.22f,.55f,1f};
        for(int i=0;i<3;i++) {
            float start=extent*(1-2*amount);
            float end=start+extent*feathers[i]*(1-amount);
            Shader mask=new LinearGradient(width/2f+dx*start,height/2f+dy*start,
                    width/2f+dx*end,height/2f+dy*end,
                    new int[]{Color.TRANSPARENT,Color.WHITE},null,Shader.TileMode.CLAMP);
            RenderEffect blur=RenderEffect.createBlurEffect(Math.max(.1f,maxRadius*radii[i]),
                    Math.max(.1f,maxRadius*radii[i]),Shader.TileMode.CLAMP);
            // Subtle refraction and translucent tint reveal live page content beneath the material.
            blur=RenderEffect.createOffsetEffect(-dx*density*amount*(1-amount)*radii[i],-dy*density*amount*(1-amount)*radii[i],blur);
            blur=RenderEffect.createColorFilterEffect(new PorterDuffColorFilter(tint,PorterDuff.Mode.SRC_ATOP),blur);
            RenderEffect masked=RenderEffect.createBlendModeEffect(blur,RenderEffect.createShaderEffect(mask),BlendMode.DST_IN);
            result=RenderEffect.createBlendModeEffect(result,masked,BlendMode.SRC_OVER);
        }
        view.setRenderEffect(result);
    }
}
