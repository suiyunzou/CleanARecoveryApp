package com.example.cleanrecovery.ui.browser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.update.GitHubUpdates;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GitHubUpdatesTest {
    private JSONObject release()throws Exception{
        JSONObject asset=new JSONObject().put("name","CleanARecovery-3.apk").put("state","uploaded").put("size",60000000).put("digest","sha256:"+"ab".repeat(32)).put("browser_download_url",GitHubUpdates.RELEASES+"/download/v0.1.2/CleanARecovery-3.apk");
        return new JSONObject().put("tag_name","v0.1.2").put("body","修复与改进").put("assets",new JSONArray().put(asset));
    }
    @Test public void selectsVersionCodeAndPreservesReleaseNotes()throws Exception{GitHubUpdates.Release r=GitHubUpdates.parse(release().toString());assertNotNull(r);assertEquals(3,r.code);assertEquals("修复与改进",r.notes);assertEquals(64,r.sha256.length());assertEquals(GitHubUpdates.RELEASES+"/tag/v0.1.2",r.pageUrl());}
    @Test public void rejectsDraftPrereleaseMissingDigestAndWrongDownloadOrigin()throws Exception{
        assertNull(GitHubUpdates.parse(release().put("draft",true).toString()));assertNull(GitHubUpdates.parse(release().put("prerelease",true).toString()));
        JSONObject obj=release();obj.getJSONArray("assets").getJSONObject(0).remove("digest");assertNull(GitHubUpdates.parse(obj.toString()));
        obj=release();obj.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://example.invalid/app.apk");assertNull(GitHubUpdates.parse(obj.toString()));
    }
    @Test public void rejectsInsecureTransportBeforeRequest()throws Exception{try{GitHubUpdates.open("http://github.com/app.apk");fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("不受支持"));}}
    @Test public void rejectsSameVersionAndOtherPackage()throws Exception{
        android.content.Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try{GitHubUpdates.validateApk(target,new java.io.File(target.getApplicationInfo().sourceDir),GitHubUpdates.installedCode(target));fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("版本"));}
        android.content.Context test=InstrumentationRegistry.getInstrumentation().getContext();
        try{GitHubUpdates.validateApk(target,new java.io.File(test.getApplicationInfo().sourceDir),3);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("名称"));}
    }
}
