package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.BrowserActivity;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserToolbarParityUiTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private android.content.SharedPreferences storage;
    private Map<String,?> saved;
    private Activity activity;
    private BrowserPrefs prefs;
    interface Work<T>{T run()throws Exception;}
    private <T>T main(Work<T> work)throws Exception{FutureTask<T> t=new FutureTask<>(work::run);instrument.runOnMainSync(t);return t.get();}
    private Object field(Object o,String name)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private Object invoke(String name,Class<?>[] types,Object... args)throws Exception{java.lang.reflect.Method m=BrowserActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    @Before public void setup()throws Exception{
        prefs=new BrowserPrefs(instrument.getTargetContext());storage=(android.content.SharedPreferences)field(prefs,"sp");saved=new HashMap<>(storage.getAll());
        prefs.setToolbarMode(1);prefs.setNightMode(false);prefs.resetMenuConfiguration();
        prefs.setRestoreTabs(0);prefs.setHomeMode(0);
        activity=instrument.startActivitySync(new Intent(instrument.getTargetContext(),BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(()->{invoke("showHome",new Class[]{});return null;});
    }
    @After public void restore()throws Exception{
        if(activity!=null)main(()->{activity.finish();return null;});instrument.waitForIdleSync();
        android.content.SharedPreferences.Editor e=storage.edit().clear();
        for(Map.Entry<String,?> item:saved.entrySet()){
            Object v=item.getValue();String k=item.getKey();
            if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
            else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);
            else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));
        }e.commit();
    }
    private void snapshot(String name)throws Exception{
        Thread.sleep(600);android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(instrument.getTargetContext().getExternalFilesDir(null),name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();
    }
    @Test public void editorArrowAndScannerActOnCurrentTab()throws Exception{
        main(()->{invoke("focusAddressBar",new Class[]{});return null;});Thread.sleep(250);
        TabManager tabs=main(()->(TabManager)field(activity,"tabs"));int id=main(()->tabs.current().id),count=main(tabs::size);
        main(()->{
            assertTrue(activity.findViewById(R.id.browser_edit_scan).isShown());
            assertTrue(activity.findViewById(R.id.browser_url_go) instanceof ImageView);
            assertTrue(activity.findViewById(R.id.browser_url_go).isShown());
            assertFalse(activity.findViewById(R.id.browser_app_home).isShown());
            Rect scan=new Rect(),go=new Rect();activity.findViewById(R.id.browser_edit_scan).getGlobalVisibleRect(scan);activity.findViewById(R.id.browser_url_go).getGlobalVisibleRect(go);
            assertTrue(scan.right<=go.left);assertEquals(scan.height(),go.height());return null;
        });snapshot("browser-search-actions");
        main(()->{((EditText)activity.findViewById(R.id.browser_url_input)).setText("https://arrow-fixture.invalid/path");activity.findViewById(R.id.browser_url_go).performClick();return null;});
        assertEquals(id,(int)main(()->tabs.current().id));assertEquals(count,(int)main(tabs::size));
        assertEquals("https://arrow-fixture.invalid/path",main(()->tabs.current().url));
        Intent result=new Intent().putExtra("SCAN_RESULT","https://scan-fixture.invalid/result").putExtra("SCAN_RESULT_FORMAT","QR_CODE");
        android.app.Instrumentation.ActivityMonitor monitor=instrument.addMonitor("com.example.cleanrecovery.ui.activity.BrowserQrScannerActivity",new android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,result),true);
        try{
            main(()->{invoke("focusAddressBar",new Class[]{});activity.findViewById(R.id.browser_edit_scan).performClick();return null;});
            instrument.waitForIdleSync();Thread.sleep(250);assertEquals(1,monitor.getHits());
            assertEquals(id,(int)main(()->tabs.current().id));assertEquals(count,(int)main(tabs::size));
            assertEquals("https://scan-fixture.invalid/result",main(()->tabs.current().url));
        }finally{instrument.removeMonitor(monitor);}
    }
    @Test public void reloadButtonStopsLoadingAndThenReloadsOnlyCurrentPage()throws Exception{
        TabManager tabs=main(()->(TabManager)field(activity,"tabs"));TabManager.Tab tab=main(tabs::current);WebView original=main(()->tab.webView);
        RecordingWebView recording=main(()->new RecordingWebView(activity));
        try{
            main(()->{tab.webView=recording;tab.url="https://loading-fixture.invalid/";invoke("hideHome",new Class[]{});invoke("configureCustomTabTopAction",new Class[]{});return null;});
            main(()->{assertEquals("停止加载",activity.findViewById(R.id.browser_reload).getContentDescription());activity.findViewById(R.id.browser_reload).performClick();assertEquals(1,recording.stops);assertEquals(0,recording.reloads);activity.findViewById(R.id.browser_reload).performClick();assertEquals(1,recording.reloads);return null;});
        }finally{main(()->{tab.webView=original;recording.destroy();return null;});}
    }
    private static final class RecordingWebView extends WebView{
        int progress=30,stops,reloads;
        RecordingWebView(android.content.Context c){super(c);}
        @Override public int getProgress(){return progress;}
        @Override public void stopLoading(){stops++;progress=100;}
        @Override public void reload(){reloads++;progress=30;}
    }
    @Test public void siteOverridesDriveMenuHighlightAndDoNotChangeOtherSites()throws Exception{
        prefs.setDesktopMode(false);prefs.setImagesEnabled(true);
        String host="scope-fixture.invalid";prefs.setSiteSettingsEnabled(host,true);prefs.setSiteDesktopMode(host,1);prefs.setSiteImagesMode(host,0);
        main(()->{
            TabManager.Tab current=((TabManager)field(activity,"tabs")).current();current.url="https://"+host+"/";
            List<BrowserBottomMenu.Entry> entries=(List<BrowserBottomMenu.Entry>)invoke("buildMenuEntries",new Class[]{});
            assertTrue(entry(entries,R.id.menu_ua).highlighted);assertTrue(entry(entries,R.id.menu_image_mode).highlighted);
            invoke("onMenuAction",new Class[]{int.class},R.id.menu_ua);invoke("onMenuAction",new Class[]{int.class},R.id.menu_image_mode);
            assertEquals(0,prefs.siteDesktopMode(host));assertEquals(1,prefs.siteImagesMode(host));assertFalse(prefs.desktopMode());assertTrue(prefs.imagesEnabled());
            current.url="https://unrelated-fixture.invalid/";entries=(List<BrowserBottomMenu.Entry>)invoke("buildMenuEntries",new Class[]{});
            assertFalse(entry(entries,R.id.menu_ua).highlighted);assertFalse(entry(entries,R.id.menu_image_mode).highlighted);return null;
        });
    }
    private BrowserBottomMenu.Entry entry(List<BrowserBottomMenu.Entry> entries,int id){for(BrowserBottomMenu.Entry e:entries)if(e.id==id)return e;throw new AssertionError("Missing "+id);}
    @Test public void customizerUsesDistinctIconsAndResetStaysOnPage()throws Exception{
        Dialog dialog=main(()->BrowserBottomMenu.showCustomizer(activity,prefs,(List<BrowserBottomMenu.Entry>)invoke("buildMenuEntries",new Class[]{})));
        try{
            Thread.sleep(350);snapshot("browser-menu-customizer-icons");
            main(()->{
                View root=dialog.getWindow().getDecorView();List<RecyclerView> grids=new ArrayList<>();findGrids(root,grids);assertEquals(2,grids.size());
                RecyclerView available=grids.get(1);int before=grids.get(0).getAdapter().getItemCount();
                RecyclerView.ViewHolder candidate=available.findViewHolderForAdapterPosition(0);assertNotNull(candidate);candidate.itemView.performClick();
                assertEquals(before+1,grids.get(0).getAdapter().getItemCount());
                View reset=findText(root,"重置");assertNotNull(reset);reset.performClick();assertTrue(dialog.isShowing());
                assertEquals(before,grids.get(0).getAdapter().getItemCount());
                TextView heading=(TextView)findText(root,"定制菜单");assertTrue((heading.getGravity()&android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)==android.view.Gravity.START);
                return null;
            });
        }finally{main(()->{dialog.dismiss();return null;});}
    }
    @Test public void internalHomeAndCloseTabNeverLoadChromeHome()throws Exception{
        main(()->{
            prefs.setHomeMode(0);
            TabManager tabs=(TabManager)field(activity,"tabs");
            TabManager.Tab home=(TabManager.Tab)invoke("newTab",new Class[]{String.class},"chrome://home/");
            assertEquals("",home.url);assertNotEquals("chrome://home/",home.webView.getUrl());
            invoke("newTab",new Class[]{String.class},"about:blank");
            invoke("closeCurrentTab",new Class[]{});
            assertTrue((Boolean)invoke("isHomeVisible",new Class[]{}));
            assertEquals("",tabs.current().url);
            assertTrue(activity.findViewById(R.id.browser_app_home).isShown());
            invoke("loadAddress",new Class[]{String.class},"https://home-button-fixture.invalid/");
            assertFalse(activity.findViewById(R.id.browser_app_home).isShown());
            activity.findViewById(R.id.browser_home).performClick();
            assertTrue((Boolean)invoke("isHomeVisible",new Class[]{}));
            return null;
        });
    }
    @Test public void suggestionFillDoesNotNavigateAndClearKeepsEditing()throws Exception{
        final AddressSuggestPopup[] suggestion=new AddressSuggestPopup[1];final int[] fills={0},picks={0};
        main(()->{
            invoke("focusAddressBar",new Class[]{});
            suggestion[0]=new AddressSuggestPopup(activity,new AddressSuggestPopup.Host(){
                public void buildLocalRows(String q,List<AddressSuggestPopup.Row> rows){rows.add(new AddressSuggestPopup.Row(AddressSuggestPopup.KIND_HISTORY,"示例网页","https://example.invalid/"));rows.add(new AddressSuggestPopup.Row(AddressSuggestPopup.KIND_SUGGEST,"示例搜索",""));}
                public String engineEndpoint(String q){return null;}public List<String> parseEngineResponse(String e,String b){return Collections.emptyList();}
                public void onPick(AddressSuggestPopup.Row row){picks[0]++;}public void onFill(AddressSuggestPopup.Row row){fills[0]++;}
            });suggestion[0].setAnchor(activity.findViewById(R.id.browser_toolbar));suggestion[0].onTextChanged("示例");return null;
        });
        try{instrument.waitForIdleSync();snapshot("browser-suggestions-refined");main(()->{
            android.widget.ListView list=(android.widget.ListView)field(suggestion[0],"list");
            assertEquals(0,list.getDividerHeight());View row=list.getAdapter().getView(0,null,list);
            row.findViewById(0x01010104).performClick();assertEquals(1,fills[0]);assertEquals(0,picks[0]);assertTrue(suggestion[0].isShowing());
            ((EditText)activity.findViewById(R.id.browser_url_input)).setText("abc");activity.findViewById(R.id.browser_edit_clear).performClick();
            assertEquals("",((EditText)activity.findViewById(R.id.browser_url_input)).getText().toString());assertTrue(activity.findViewById(R.id.browser_url_input).hasFocus());return null;
        });}finally{main(()->{suggestion[0].destroy();return null;});}
    }
    @Test public void blockedPopupsRequireConsentEvenWithClickGesture()throws Exception{
        main(()->{
            storage.edit().remove("popups_enabled").commit();
            assertFalse(prefs.popupsEnabled());
            TabManager tabs=(TabManager)field(activity,"tabs");
            WebView source=tabs.current().webView;
            int count=tabs.size();
            for(boolean gesture:new boolean[]{false,true}){
                android.os.Message result=android.os.Message.obtain(new android.os.Handler(android.os.Looper.getMainLooper()));
                WebView.WebViewTransport transport=source.new WebViewTransport();result.obj=transport;
                assertTrue(source.getWebChromeClient().onCreateWindow(source,false,gesture,result));
                assertEquals(count,tabs.size());assertSame(source,tabs.current().webView);
                assertNotNull(field(activity,"popupPrompt"));
                invoke("dismissPopupPrompt",new Class[]{});
                assertNull(transport.getWebView());assertEquals(count,tabs.size());
            }
            return null;
        });
    }
    @Test public void popupConsentCreatesExactlyOneTabAndSettingCanAllowDirectly()throws Exception{
        main(()->{
            prefs.setPopupsEnabled(false);
            TabManager tabs=(TabManager)field(activity,"tabs");WebView source=tabs.current().webView;
            int count=tabs.size();
            android.os.Message result=android.os.Message.obtain(new android.os.Handler(android.os.Looper.getMainLooper()));
            WebView.WebViewTransport transport=source.new WebViewTransport();result.obj=transport;
            assertTrue(source.getWebChromeClient().onCreateWindow(source,false,true,result));
            com.google.android.material.snackbar.Snackbar prompt=(com.google.android.material.snackbar.Snackbar)field(activity,"popupPrompt");
            prompt.getView().findViewById(com.google.android.material.R.id.snackbar_action).performClick();
            assertEquals(count+1,tabs.size());assertSame(tabs.current().webView,transport.getWebView());
            invoke("dismissPopupPrompt",new Class[]{});assertSame(tabs.current().webView,transport.getWebView());
            prefs.setPopupsEnabled(true);source=tabs.current().webView;
            android.os.Message allowed=android.os.Message.obtain(new android.os.Handler(android.os.Looper.getMainLooper()));
            WebView.WebViewTransport allowedTransport=source.new WebViewTransport();allowed.obj=allowedTransport;
            assertTrue(source.getWebChromeClient().onCreateWindow(source,false,false,allowed));
            assertEquals(count+2,tabs.size());assertSame(tabs.current().webView,allowedTransport.getWebView());
            return null;
        });
    }
    @Test public void switchingTabsCancelsPendingPopup()throws Exception{
        main(()->{
            prefs.setPopupsEnabled(false);
            TabManager tabs=(TabManager)field(activity,"tabs");WebView source=tabs.current().webView;
            android.os.Message result=android.os.Message.obtain(new android.os.Handler(android.os.Looper.getMainLooper()));
            WebView.WebViewTransport transport=source.new WebViewTransport();result.obj=transport;
            assertTrue(source.getWebChromeClient().onCreateWindow(source,false,false,result));
            com.google.android.material.snackbar.Snackbar prompt=(com.google.android.material.snackbar.Snackbar)field(activity,"popupPrompt");
            invoke("newTab",new Class[]{String.class},(Object)null);int count=tabs.size();
            prompt.getView().findViewById(com.google.android.material.R.id.snackbar_action).performClick();
            assertEquals(count,tabs.size());assertNull(transport.getWebView());return null;
        });
    }
    @Test public void viaDialogKeepsValidationAndCancellationCallbacks()throws Exception{
        final int[] cancelled={0},savedValues={0};
        final EditText[] input={null};
        android.app.AlertDialog dialog=main(()->{
            input[0]=new EditText(activity);
            android.app.AlertDialog shown=new ViaDialogBuilder(activity).setTitle("编辑名称")
                    .setView(input[0]).setNegativeButton("取消",null).setPositiveButton("保存",null)
                    .setOnCancelListener(d->cancelled[0]++).create();
            shown.setOnShowListener(d->shown.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(input[0].getText().length()==0){input[0].setError("请输入名称");return;}
                savedValues[0]++;shown.dismiss();
            }));shown.show();return shown;
        });
        try{main(()->{
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());assertEquals(0,savedValues[0]);
            input[0].setText("示例");dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(dialog.isShowing());assertEquals(1,savedValues[0]);
            dialog.show();dialog.cancel();return null;
        });instrument.waitForIdleSync();assertEquals(1,cancelled[0]);}finally{main(()->{dialog.dismiss();return null;});}
    }
    @Test public void viaDialogRestoreAndChoicePreviews()throws Exception{
        android.app.AlertDialog restore=main(()->new ViaDialogBuilder(activity).setTitle("恢复未关闭标签")
                .setMessage("是否恢复上次未关闭的标签？").setNegativeButton("取消",null).setPositiveButton("恢复",null).show());
        try{snapshot("via-restore-dialog");main(()->{
            assertEquals(ViaUi.ACCENT,restore.getButton(android.app.AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());
            assertTrue(restore.getWindow().getAttributes().width<=ViaUi.dp(activity,350));return null;
        });}finally{main(()->{restore.dismiss();return null;});}
        final int[] picked={-1};
        android.app.AlertDialog choice=main(()->new ViaDialogBuilder(activity).setTitle("弹出式窗口")
                .setSingleChoiceItems(new String[]{"允许","阻止"},1,(d,w)->picked[0]=w).show());
        try{snapshot("via-choice-dialog");main(()->{
            assertEquals(1,choice.getListView().getCheckedItemPosition());
            choice.getListView().performItemClick(choice.getListView().getChildAt(0),0,0);assertEquals(0,picked[0]);return null;
        });}finally{main(()->{choice.dismiss();return null;});}
        main(()->{prefs.setNightMode(true);return null;});
        android.app.AlertDialog dark=main(()->new ViaDialogBuilder(activity).setTitle("恢复未关闭标签")
                .setMessage("是否恢复上次未关闭的标签？").setNegativeButton("取消",null).setPositiveButton("恢复",null).show());
        try{snapshot("via-restore-dialog-night");main(()->{
            TextView message=dark.findViewById(android.R.id.message);assertEquals(0xFF999999,message.getCurrentTextColor());return null;
        });}finally{main(()->{dark.dismiss();return null;});}
    }
    @Test public void portraitScannerGalleryResultReturnsToCurrentTab()throws Exception{
        String expected="https://qr-gallery-fixture.invalid/result";
        java.io.File dir=new java.io.File(instrument.getTargetContext().getCacheDir(),"share");dir.mkdirs();
        java.io.File fixture=new java.io.File(dir,"qr-gallery-test.png");
        com.google.zxing.common.BitMatrix bits=new com.google.zxing.qrcode.QRCodeWriter().encode(expected,com.google.zxing.BarcodeFormat.QR_CODE,640,640);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(640,640,android.graphics.Bitmap.Config.ARGB_8888);
        for(int y=0;y<640;y++)for(int x=0;x<640;x++)bitmap.setPixel(x,y,bits.get(x,y)?android.graphics.Color.BLACK:android.graphics.Color.WHITE);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(fixture)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(instrument.getTargetContext(),instrument.getTargetContext().getPackageName()+".fileprovider",fixture);
        android.app.Instrumentation.ActivityMonitor scannerMonitor=instrument.addMonitor("com.example.cleanrecovery.ui.activity.BrowserQrScannerActivity",null,false);
        android.content.IntentFilter pickerFilter=new android.content.IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        pickerFilter.addCategory(Intent.CATEGORY_OPENABLE);pickerFilter.addDataType("image/*");
        android.app.Instrumentation.ActivityMonitor picker=instrument.addMonitor(pickerFilter,new android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(uri)),true);
        Activity scanner=null;
        try{
            int tabId=main(()->((TabManager)field(activity,"tabs")).current().id);
            main(()->{invoke("launchQrScanner",new Class[]{});return null;});
            scanner=instrument.waitForMonitorWithTimeout(scannerMonitor,5000);assertNotNull(scanner);Activity screen=scanner;
            instrument.waitForIdleSync();
            main(()->{assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,screen.getRequestedOrientation());assertTrue(screen.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT);assertNotNull(findDescription(screen.getWindow().getDecorView(),"打开手电筒"));return null;});
            snapshot("browser-portrait-scanner");
            main(()->{findDescription(screen.getWindow().getDecorView(),"从图库识别二维码").performClick();return null;});
            long deadline=android.os.SystemClock.uptimeMillis()+6000;
            while(android.os.SystemClock.uptimeMillis()<deadline&&!expected.equals(main(()->((TabManager)field(activity,"tabs")).current().url)))Thread.sleep(50);
            assertEquals(1,picker.getHits());assertEquals(expected,main(()->((TabManager)field(activity,"tabs")).current().url));assertEquals(tabId,(int)main(()->((TabManager)field(activity,"tabs")).current().id));
        }finally{if(scanner!=null){Activity screen=scanner;main(()->{if(!screen.isFinishing())screen.finish();return null;});}instrument.removeMonitor(scannerMonitor);instrument.removeMonitor(picker);fixture.delete();}
    }
    private View findDescription(View v,String text){if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findDescription(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void findGrids(View v,List<RecyclerView> result){if(v instanceof RecyclerView){result.add((RecyclerView)v);return;}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)findGrids(((ViewGroup)v).getChildAt(i),result);}
    private View findText(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findText(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
}
