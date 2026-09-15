package com.example.cleanrecovery.update;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GitHubUpdatesAbiTest {
    private JSONObject asset(long code, String abi) throws Exception {
        String name = "CleanARecovery-" + code + (abi == null ? "" : "-" + abi) + ".apk";
        return new JSONObject().put("name", name).put("state", "uploaded").put("size", 12345)
                .put("digest", "sha256:" + "ab".repeat(32))
                .put("browser_download_url", GitHubUpdates.RELEASES + "/download/v1/" + name);
    }
    private String release(JSONObject... assets) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject asset : assets) array.put(asset);
        return new JSONObject().put("tag_name", "v1").put("body", "notes").put("assets", array).toString();
    }
    @Test public void arm64PrefersItsOwnApkRegardlessOfAssetOrder() throws Exception {
        JSONObject arm = asset(10, "arm64-v8a"), universal = asset(10, null), arm32 = asset(10, "armeabi-v7a");
        for (String json : new String[]{release(universal, arm32, arm), release(arm, universal, arm32)}) {
            GitHubUpdates.Release found = GitHubUpdates.parse(json, new String[]{"arm64-v8a", "armeabi-v7a"});
            assertTrue(found.url.endsWith("-arm64-v8a.apk"));
            assertEquals("notes", found.notes);
            assertEquals(GitHubUpdates.RELEASES + "/tag/v1", found.pageUrl());
        }
    }
    @Test public void emulatorPrefersNativeAbiBeforeTranslatedArm() throws Exception {
        GitHubUpdates.Release found = GitHubUpdates.parse(release(asset(10, "arm64-v8a"), asset(10, null), asset(10, "x86_64")),
                new String[]{"x86_64", "x86", "arm64-v8a"});
        assertTrue(found.url.endsWith("-x86_64.apk"));
    }
    @Test public void supportsBoth32BitTargets() throws Exception {
        for (String abi : new String[]{"armeabi-v7a", "x86"})
            assertTrue(GitHubUpdates.parse(release(asset(10, null), asset(10, abi)), new String[]{abi}).url.endsWith("-" + abi + ".apk"));
    }
    @Test public void fallsBackToUniversalWhenDeviceApkIsAbsent() throws Exception {
        assertTrue(GitHubUpdates.parse(release(asset(10, "x86_64"), asset(10, null)), new String[]{"arm64-v8a"}).url.endsWith("-10.apk"));
    }
    @Test public void supportsLegacyReleasesAndUnknownAbis() throws Exception {
        for (String[] abis : new String[][]{null, {}, {"riscv64"}, {"arm64-v8a"}})
            assertEquals(10, GitHubUpdates.parse(release(asset(10, null)), abis).code);
    }
    @Test public void neverOffersAnIncompatibleArchitecture() throws Exception {
        assertNull(GitHubUpdates.parse(release(asset(10, "arm64-v8a")), new String[]{"x86_64"}));
    }
    @Test public void newerVersionWinsBeforeArchitecturePreference() throws Exception {
        assertEquals(11, GitHubUpdates.parse(release(asset(10, "arm64-v8a"), asset(11, null)), new String[]{"arm64-v8a"}).code);
    }
    @Test public void badPreferredAssetDoesNotHideValidUniversal() throws Exception {
        JSONObject[] invalid = {
                asset(10, "arm64-v8a").put("digest", "sha256:bad"),
                asset(10, "arm64-v8a").put("state", "new"),
                asset(10, "arm64-v8a").put("size", GitHubUpdates.MAX_APK + 1),
                asset(10, "arm64-v8a").put("browser_download_url", "https://example.invalid/app.apk")
        };
        for (JSONObject candidate : invalid)
            assertTrue(GitHubUpdates.parse(release(candidate, asset(10, null)), new String[]{"arm64-v8a"}).url.endsWith("-10.apk"));
    }
    @Test public void unpublishedReleasesAreRejected() throws Exception {
        for (String field : new String[]{"draft", "prerelease"})
            assertNull(GitHubUpdates.parse(new JSONObject(release(asset(10, "arm64-v8a"))).put(field, true).toString(), new String[]{"arm64-v8a"}));
    }
}
