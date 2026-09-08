package com.example.cleanrecovery.update;

import android.content.Context;
import android.content.pm.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public GitHub release client. No credentials are packaged in the application. */
public final class GitHubUpdates {
    public static final String REPOSITORY = "suiyunzou/CleanARecoveryApp";
    public static final String RELEASES = "https://github.com/"+REPOSITORY+"/releases";
    public static final long MAX_APK = 300L*1024*1024;
    public static final class Release {
        public final long code,size; public final String name,notes,url,sha256;
        Release(long code,long size,String name,String notes,String url,String sha){this.code=code;this.size=size;this.name=name;this.notes=notes;this.url=url;this.sha256=sha;}
        public String pageUrl(){return url.substring(0,url.lastIndexOf('/')).replace(RELEASES+"/download/",RELEASES+"/tag/");}
    }
    public interface Progress { void accept(int percent); }
    private GitHubUpdates() {}
    public static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("app_updates",Context.MODE_PRIVATE);}
    public static long installedCode(Context c)throws PackageManager.NameNotFoundException{return code(c.getPackageManager().getPackageInfo(c.getPackageName(),0));}
    private static long code(PackageInfo p){return android.os.Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    public static Release parse(String json)throws Exception {
        JSONObject obj=new JSONObject(json);
        if(obj.optBoolean("draft")||obj.optBoolean("prerelease"))return null;
        JSONArray assets=obj.optJSONArray("assets"); if(assets==null)return null;
        Release selected=null;
        for(int i=0;i<assets.length();i++){
            JSONObject a=assets.getJSONObject(i);
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("CleanARecovery-([1-9][0-9]*)\\.apk").matcher(a.optString("name"));
            if(!m.matches()||!"uploaded".equals(a.optString("state")))continue;
            long version=Long.parseLong(m.group(1)),size=a.optLong("size");String url=a.optString("browser_download_url"),sha=a.optString("digest");
            if(!url.startsWith(RELEASES+"/download/")||!sha.matches("sha256:[0-9a-fA-F]{64}")||size<=0||size>MAX_APK)continue;
            if(selected==null||version>selected.code)selected=new Release(version,size,obj.optString("tag_name"),obj.optString("body"),url,sha.substring(7));
        }
        return selected;
    }
    public static Release check()throws Exception{
        // Public release asset avoids the shared anonymous REST API rate limit.
        try {
            return checkAt(RELEASES+"/latest/download/update.json");
        } catch (IOException manifestUnavailable) {
            return checkAt("https://api.github.com/repos/"+REPOSITORY+"/releases/latest");
        }
    }
    private static Release checkAt(String address)throws Exception{
        HttpURLConnection c=open(address);
        try{int status=c.getResponseCode();if(status==404)throw new IOException("尚未发布可用的公开版本");if(status==403||status==429)throw new IOException("GitHub 请求受限，请稍后再试");if(status!=200)throw new IOException("检查失败（HTTP "+status+"）");
            try(InputStream in=c.getInputStream()){Release r=parse(new String(read(in,1024*1024),java.nio.charset.StandardCharsets.UTF_8));if(r==null)throw new IOException("该版本尚未提供兼容的更新安装包");return r;}
        }finally{c.disconnect();}
    }
    public static HttpURLConnection open(String location)throws IOException{
        for(int hop=0;hop<6;hop++){
            URL url=new URL(location);String host=url.getHost().toLowerCase(Locale.ROOT);
            if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||!(host.equals("api.github.com")||host.equals("github.com")||host.endsWith(".githubusercontent.com")))throw new IOException("更新地址不受支持");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","CleanARecovery-Android");
            int status=c.getResponseCode();if(status>=300&&status<400){String next=c.getHeaderField("Location");c.disconnect();if(next==null)throw new IOException("更新地址跳转无效");location=new URL(url,next).toString();continue;}return c;
        }throw new IOException("更新地址跳转过多");
    }
    private static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("更新信息过大");out.write(b,0,n);}return out.toByteArray();}
    public static File download(Context context,Release r,AtomicBoolean cancelled,Progress progress)throws Exception{
        File dir=new File(context.getCacheDir(),"updates");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建下载目录");
        File part=File.createTempFile("update-",".part",dir);boolean ready=false;
        try{HttpURLConnection c=open(r.url);try{if(c.getResponseCode()!=200)throw new IOException("下载失败（HTTP "+c.getResponseCode()+"）");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;int previous=-1;
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(part)){byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1){if(cancelled.get()||Thread.currentThread().isInterrupted())throw new IOException("已取消下载");size+=n;if(size>r.size||size>MAX_APK)throw new IOException("安装包大小不符");digest.update(bytes,0,n);out.write(bytes,0,n);int percent=(int)(size*100/r.size);if(percent!=previous){progress.accept(percent);previous=percent;}}}
            if(size!=r.size||!hex(digest.digest()).equalsIgnoreCase(r.sha256))throw new IOException("安装包校验失败，请重试");
        }finally{c.disconnect();}
            validateApk(context,part,r.code);if(cancelled.get())throw new IOException("已取消下载");
            File result=new File(dir,"update-"+r.code+".apk");if(result.exists()&&!result.delete())throw new IOException("无法替换缓存安装包");if(!part.renameTo(result))throw new IOException("无法保存安装包");ready=true;return result;
        }finally{if(!ready)part.delete();}
    }
    public static void validateApk(Context context,File apk,long expected)throws Exception{
        PackageManager pm=context.getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(apk.getPath(),PackageManager.GET_SIGNATURES),installed=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);
        if(archive==null||!context.getPackageName().equals(archive.packageName)||code(archive)!=expected||expected<=code(installed))throw new IOException("安装包名称或版本不匹配");
        if(archive.applicationInfo!=null&&android.os.Build.VERSION.SDK_INT>=24&&archive.applicationInfo.minSdkVersion>android.os.Build.VERSION.SDK_INT)throw new IOException("新版不支持当前 Android 版本");
        if(archive.signatures==null||installed.signatures==null||!new HashSet<>(Arrays.asList(archive.signatures)).equals(new HashSet<>(Arrays.asList(installed.signatures))))throw new IOException("安装包签名与当前应用不一致");
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
