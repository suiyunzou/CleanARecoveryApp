package com.example.cleanrecovery.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.widget.GlassToast;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.*;
import java.io.InputStream;
import java.util.Collections;

/** Portrait QR camera and document-picker decoding share one result contract. */
public final class BrowserQrScannerActivity extends Activity {
    private static final int CAMERA = 81, IMAGE = 82;
    private BarcodeView camera;
    private ImageButton torch;
    private boolean torchOn, completed, picking, decoding;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        androidx.core.view.WindowInsetsControllerCompat bars = new androidx.core.view.WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false); bars.setAppearanceLightNavigationBars(false);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        camera = new BarcodeView(this);
        camera.setDecoderFactory(new DefaultDecoderFactory(Collections.singleton(BarcodeFormat.QR_CODE)));
        root.addView(camera, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->{
            int side=(int)Math.min((r-l)*.75f,(b-t)*.48f);
            if(side>0)camera.setFramingRectSize(new Size(side,side));
        });
        root.addView(new Finder(), new FrameLayout.LayoutParams(-1, -1));
        FrameLayout controls = new FrameLayout(this); root.addView(controls, new FrameLayout.LayoutParams(-1,-1));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(controls, (v,insets)->{
            androidx.core.graphics.Insets safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom); return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = button(R.drawable.via_toolbar_back,"返回");
        back.setBackgroundColor(Color.TRANSPARENT); back.setOnClickListener(v->finish());
        header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView title = new TextView(this); title.setText("扫描二维码"); title.setTextColor(Color.WHITE); title.setTextSize(20); title.setTypeface(null,Typeface.BOLD);
        header.addView(title); controls.addView(header,new FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP));
        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(44),0,dp(44),0);
        torch = button(R.drawable.ic_qr_torch,"打开手电筒");
        boolean flash = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        torch.setEnabled(flash); torch.setAlpha(flash?1f:0.35f);
        torch.setOnClickListener(v->{
            if (checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA);return; }
            torchOn=!torchOn; camera.setTorch(torchOn); torch.setContentDescription(torchOn?"关闭手电筒":"打开手电筒"); torch.setSelected(torchOn);
        });
        bottom.addView(torch,new LinearLayout.LayoutParams(dp(52),dp(52)));
        bottom.addView(new Space(this),new LinearLayout.LayoutParams(0,1,1));
        ImageButton gallery = button(R.drawable.via_action_image_mode,"从图库识别二维码");
        gallery.setOnClickListener(v->{ picking=true; stopCamera(); startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),IMAGE); });
        bottom.addView(gallery,new LinearLayout.LayoutParams(dp(52),dp(52)));
        FrameLayout.LayoutParams footer = new FrameLayout.LayoutParams(-1,dp(72),Gravity.BOTTOM); footer.bottomMargin=dp(26); controls.addView(bottom,footer);
        setContentView(root);
        camera.decodeContinuous(new BarcodeCallback(){
            @Override public void barcodeResult(BarcodeResult result){ if(!picking&&!decoding) deliver(result.getText()); }
        });
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA);
    }
    private ImageButton button(int icon,String label){
        ImageButton b=new ImageButton(this);b.setImageResource(icon);b.setColorFilter(Color.WHITE);b.setContentDescription(label);b.setPadding(dp(13),dp(13),dp(13),dp(13));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0x33FFFFFF);bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);b.setBackground(bg);return b;
    }
    private void resumeCamera(){if(!completed&&!picking&&!decoding&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera.resume();}
    private void stopCamera(){torchOn=false;camera.setTorch(false);torch.setContentDescription("打开手电筒");camera.pause();}
    @Override protected void onResume(){super.onResume();resumeCamera();}
    @Override protected void onPause(){stopCamera();super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==CAMERA){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)resumeCamera();else GlassToast.makeText(this,"未开启摄像头权限，仍可从图库识别",GlassToast.LENGTH_LONG).show();}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=IMAGE)return;picking=false;
        if(result!=RESULT_OK||data==null||data.getData()==null){resumeCamera();return;}
        decoding=true; android.net.Uri uri=data.getData();
        worker.execute(()->{String text=null;try{text=decodeImage(uri);}catch(Exception|OutOfMemoryError ignored){}
            final String decoded=text;runOnUiThread(()->{decoding=false;if(isFinishing()||isDestroyed())return;if(decoded!=null)deliver(decoded);else{GlassToast.makeText(this,"未识别到二维码，请选择清晰图片",GlassToast.LENGTH_LONG).show();resumeCamera();}});});
    }
    private String decodeImage(android.net.Uri uri)throws Exception{
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,opts);}
        opts.inSampleSize=1;while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>2048)opts.inSampleSize*=2;
        opts.inJustDecodeBounds=false;Bitmap bitmap;
        try(InputStream in=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,opts);}
        if(bitmap==null)return null;
        try{int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
            LuminanceSource source=new RGBLuminanceSource(w,h,pixels);java.util.Map<DecodeHintType,Object> hints=new java.util.EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,true);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singleton(BarcodeFormat.QR_CODE));
            try{return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)),hints).getText();}
            catch(NotFoundException e){return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source.invert())),hints).getText();}
        }finally{bitmap.recycle();}
    }
    private void deliver(String text){if(completed||text==null)return;completed=true;stopCamera();setResult(RESULT_OK,new Intent().putExtra("SCAN_RESULT",text).putExtra("SCAN_RESULT_FORMAT","QR_CODE"));finish();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private final class Finder extends View{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Finder(){super(BrowserQrScannerActivity.this);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){float side=Math.min(getWidth()*.75f,getHeight()*.48f),x=(getWidth()-side)/2,y=(getHeight()-side)/2;
            paint.setColor(0x66000000);paint.setStyle(Paint.Style.FILL);c.drawRect(0,0,getWidth(),y,paint);c.drawRect(0,y+side,getWidth(),getHeight(),paint);c.drawRect(0,y,x,y+side,paint);c.drawRect(x+side,y,getWidth(),y+side,paint);
            paint.setColor(Color.WHITE);paint.setStrokeWidth(dp(2));float edge=dp(18);
            for(int i=0;i<4;i++){float a=i%2==0?x:x+side,b=i<2?y:y+side;c.drawLine(a,b,a+(i%2==0?edge:-edge),b,paint);c.drawLine(a,b,a,b+(i<2?edge:-edge),paint);}
        }
    }
}
