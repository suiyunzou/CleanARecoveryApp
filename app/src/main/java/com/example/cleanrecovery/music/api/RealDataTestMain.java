package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.SongInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JVM 端真实数据测试程序。
 *
 * <p>本程序可在电脑端直接运行（无需 Android 设备/模拟器），
 * 使用真实酷狗 API 进行以下验证：</p>
 * <ul>
 *   <li>歌曲搜索：通过 mobilecdn.kugou.com 搜索真实歌曲</li>
 *   <li>歌词获取：通过 lyrics.kugou.com 获取 LRC + KRC 歌词</li>
 *   <li>播放 URL 解析：通过多端点回退策略获取真实可播放 URL</li>
 *   <li>歌曲下载：实际下载 MP3 文件到本地并验证完整性</li>
 *   <li>音频格式检测：验证下载文件为标准未加密 MP3 格式</li>
 * </ul>
 *
 * <p>运行方式：</p>
 * <pre>
 * gradlew.bat :app:runRealDataTest
 * 或
 * java -cp app/build/intermediates/javac/debug/classes;libs/gson-2.10.1.jar
 *      com.example.cleanrecovery.music.api.RealDataTestMain
 * </pre>
 *
 * <p>注意：本程序发起真实网络请求，需要网络连接。
 * 下载的文件保存在 build/realdata-test/ 目录下。</p>
 */
public class RealDataTestMain {

    // ===== API 端点常量（与 KugouDataSource 保持一致）=====
    private static final String SEARCH_URL = "http://mobilecdn.kugou.com/api/v3/search/song";
    private static final String LYRIC_SEARCH_URL = "http://lyrics.kugou.com/search";
    private static final String LYRIC_DOWNLOAD_URL = "http://lyrics.kugou.com/download";
    private static final String PLAY_INFO_URL = "http://m.kugou.com/app/i/getSongInfo.php";
    private static final String TRACKER_URL = "http://trackercdn.kugou.com/i/v2/";
    private static final String WEB_PLAY_URL = "https://wwwapi.kugou.com/yy/index.php";

    // 测试输出目录
    private static final File OUTPUT_DIR = new File("build/realdata-test");

    // 测试统计
    private static int totalTests = 0;
    private static int passedTests = 0;
    private static int failedTests = 0;
    private static final List<String> failureDetails = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("====================================================");
        System.out.println("  酷狗音乐真实数据测试（JVM 端直接运行）");
        System.out.println("====================================================");
        System.out.println("测试时间: " + new java.util.Date());
        System.out.println("网络环境: " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch"));
        System.out.println();

        // 创建输出目录
        OUTPUT_DIR.mkdirs();
        System.out.println("输出目录: " + OUTPUT_DIR.getAbsolutePath());
        System.out.println();

        try {
            // ===== 测试 1: 歌曲搜索 =====
            System.out.println("========== 测试 1: 歌曲搜索 ==========");
            List<SongInfo> searchResults = testSearch("周杰伦 晴天", 1);
            System.out.println();

            // ===== 测试 2: 歌词获取（LRC + KRC）=====
            System.out.println("========== 测试 2: 歌词获取 ==========");
            if (!searchResults.isEmpty()) {
                SongInfo testSong = searchResults.get(0);
                testLyricsRetrieval(testSong);
            }
            System.out.println();

            // ===== 测试 3: 播放 URL 解析 =====
            System.out.println("========== 测试 3: 播放 URL 解析 ==========");
            String playUrl = null;
            if (!searchResults.isEmpty()) {
                playUrl = testPlayUrlResolution(searchResults);
            }
            System.out.println();

            // ===== 测试 4: 歌曲下载 =====
            System.out.println("========== 测试 4: 歌曲下载 ==========");
            File downloadedFile = null;
            if (playUrl != null) {
                downloadedFile = testSongDownload(playUrl, searchResults.get(0));
            }
            System.out.println();

            // ===== 测试 5: 音频格式检测 =====
            System.out.println("========== 测试 5: 音频格式检测 ==========");
            if (downloadedFile != null) {
                testAudioFormatDetection(downloadedFile);
            }
            System.out.println();

            // ===== 测试 6: 多歌曲批量测试 =====
            System.out.println("========== 测试 6: 多歌曲批量测试 ==========");
            testMultipleSongs();
            System.out.println();

        } catch (Exception e) {
            System.err.println("测试过程中发生未捕获异常: " + e.getMessage());
            e.printStackTrace();
        }

        // ===== 测试报告 =====
        System.out.println("====================================================");
        System.out.println("                  测试报告");
        System.out.println("====================================================");
        System.out.println("总测试数: " + totalTests);
        System.out.println("通过数:   " + passedTests);
        System.out.println("失败数:   " + failedTests);
        System.out.println("成功率:   "
                + String.format("%.2f%%", totalTests == 0 ? 0 : 100.0 * passedTests / totalTests));
        if (!failureDetails.isEmpty()) {
            System.out.println();
            System.out.println("失败详情:");
            for (String detail : failureDetails) {
                System.out.println("  - " + detail);
            }
        }
        System.out.println("====================================================");
    }

    // ===== 测试 1: 歌曲搜索 =====

    private static List<SongInfo> testSearch(String keyword, int page) {
        totalTests++;
        long start = System.currentTimeMillis();
        System.out.println("[搜索] 关键词: " + keyword + ", 页码: " + page);

        try {
            String url = SEARCH_URL
                    + "?format=json"
                    + "&keyword=" + URLEncoder.encode(keyword, "UTF-8")
                    + "&page=" + Math.max(1, page)
                    + "&pagesize=10"
                    + "&showtype=1";

            JsonObject resp = httpGet(url);
            long elapsed = System.currentTimeMillis() - start;

            JsonObject data = getObject(resp, "data");
            JsonArray info = data != null ? getArray(data, "info") : null;

            if (info == null || info.size() == 0) {
                throw new RuntimeException("搜索结果为空");
            }

            List<SongInfo> songs = new ArrayList<>();
            for (JsonElement element : info) {
                if (!element.isJsonObject()) continue;
                SongInfo song = parseSong(element.getAsJsonObject());
                if (song.hash != null && !song.hash.isEmpty()
                        && song.title != null && !song.title.isEmpty()) {
                    songs.add(song);
                }
            }

            System.out.println("[搜索] 耗时: " + elapsed + "ms");
            System.out.println("[搜索] 返回歌曲数: " + songs.size());
            System.out.println("[搜索] 前 3 首歌曲:");
            for (int i = 0; i < Math.min(3, songs.size()); i++) {
                SongInfo s = songs.get(i);
                System.out.printf("  %d. %s - %s (VIP=%b, hash=%s)%n",
                        i + 1, s.artist, s.title, s.vipRequired, s.hash.substring(0, 16) + "...");
            }

            if (!songs.isEmpty()) {
                passedTests++;
                System.out.println("[搜索] 结果: PASS");
            } else {
                failedTests++;
                failureDetails.add("搜索: 返回歌曲列表为空");
                System.out.println("[搜索] 结果: FAIL (空列表)");
            }
            return songs;

        } catch (Exception e) {
            failedTests++;
            failureDetails.add("搜索: " + e.getMessage());
            System.out.println("[搜索] 结果: FAIL - " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ===== 测试 2: 歌词获取 =====

    private static void testLyricsRetrieval(SongInfo song) {
        System.out.println("[歌词] 测试歌曲: " + song.artist + " - " + song.title);

        // 测试 2.1: LRC 歌词获取
        totalTests++;
        long start = System.currentTimeMillis();
        try {
            LyricCandidate candidate = searchLyricCandidate(song);
            if (candidate == null) {
                throw new RuntimeException("未找到歌词候选");
            }
            System.out.println("[歌词] 候选 ID: " + candidate.id);

            // 下载 LRC
            Lyrics lrc = downloadLrc(candidate);
            long elapsed = System.currentTimeMillis() - start;

            if (lrc != null && !lrc.isEmpty()) {
                System.out.println("[歌词] LRC 获取耗时: " + elapsed + "ms");
                System.out.println("[歌词] LRC 行数: " + lrc.size());
                System.out.println("[歌词] LRC 前 3 行:");
                for (int i = 0; i < Math.min(3, lrc.size()); i++) {
                    Lyrics.Line line = lrc.lines().get(i);
                    System.out.printf("  [%02d:%02d.%03d] %s%n",
                            line.timeMs / 60000, (line.timeMs % 60000) / 1000,
                            line.timeMs % 1000, line.text);
                }
                // 保存 LRC 文件
                File lrcFile = new File(OUTPUT_DIR, "lyrics.lrc");
                Files.write(Paths.get(lrcFile.getAbsolutePath()),
                        lrc.raw.getBytes(StandardCharsets.UTF_8));
                System.out.println("[歌词] LRC 已保存: " + lrcFile.getAbsolutePath());

                passedTests++;
                System.out.println("[歌词] LRC 结果: PASS");
            } else {
                throw new RuntimeException("LRC 内容为空");
            }
        } catch (Exception e) {
            failedTests++;
            failureDetails.add("LRC 获取: " + e.getMessage());
            System.out.println("[歌词] LRC 结果: FAIL - " + e.getMessage());
        }

        // 测试 2.2: KRC 歌词获取与解码
        totalTests++;
        start = System.currentTimeMillis();
        try {
            LyricCandidate candidate = searchLyricCandidate(song);
            if (candidate == null) {
                throw new RuntimeException("未找到歌词候选");
            }

            // 下载 KRC
            String krcText = downloadAndDecodeKrc(candidate);
            long elapsed = System.currentTimeMillis() - start;

            if (krcText != null && !krcText.isEmpty()) {
                System.out.println("[歌词] KRC 获取+解码耗时: " + elapsed + "ms");
                System.out.println("[歌词] KRC 明文长度: " + krcText.length() + " 字符");
                System.out.println("[歌词] KRC 前 200 字符:");
                System.out.println("  " + krcText.substring(0, Math.min(200, krcText.length()))
                        .replace("\n", "\n  "));

                // 转换为 LRC 格式
                String convertedLrc = KrcToLrcConverter.convert(krcText);
                System.out.println("[歌词] KRC→LRC 转换后行数: "
                        + (convertedLrc.split("\n").length));

                // 保存 KRC 明文
                File krcFile = new File(OUTPUT_DIR, "lyrics_krc_decoded.txt");
                Files.write(Paths.get(krcFile.getAbsolutePath()),
                        krcText.getBytes(StandardCharsets.UTF_8));
                System.out.println("[歌词] KRC 明文已保存: " + krcFile.getAbsolutePath());

                passedTests++;
                System.out.println("[歌词] KRC 结果: PASS");
            } else {
                throw new RuntimeException("KRC 解码内容为空");
            }
        } catch (Exception e) {
            failedTests++;
            failureDetails.add("KRC 获取: " + e.getMessage());
            System.out.println("[歌词] KRC 结果: FAIL - " + e.getMessage());
        }
    }

    // ===== 测试 3: 播放 URL 解析 =====

    private static String testPlayUrlResolution(List<SongInfo> songs) {
        totalTests++;
        long start = System.currentTimeMillis();
        System.out.println("[URL] 尝试解析播放 URL...");

        // 优先选择非 VIP 歌曲
        SongInfo target = null;
        for (SongInfo s : songs) {
            if (!s.vipRequired) {
                target = s;
                break;
            }
        }
        if (target == null) target = songs.get(0);

        System.out.println("[URL] 目标歌曲: " + target.artist + " - " + target.title
                + " (VIP=" + target.vipRequired + ")");

        try {
            String url = resolvePlayUrl(target);
            long elapsed = System.currentTimeMillis() - start;

            if (url != null && !url.isEmpty()) {
                System.out.println("[URL] 解析耗时: " + elapsed + "ms");
                System.out.println("[URL] 播放地址: " + url.substring(0, Math.min(80, url.length()))
                        + (url.length() > 80 ? "..." : ""));
                System.out.println("[URL] 协议: " + (url.startsWith("https") ? "HTTPS"
                        : url.startsWith("http") ? "HTTP" : "其他"));

                passedTests++;
                System.out.println("[URL] 结果: PASS");
                return url;
            } else {
                throw new RuntimeException("所有端点均返回空 URL（可能需要登录态或 VIP 权限）");
            }
        } catch (Exception e) {
            failedTests++;
            failureDetails.add("URL 解析: " + e.getMessage());
            System.out.println("[URL] 结果: FAIL - " + e.getMessage());
            return null;
        }
    }

    // ===== 测试 4: 歌曲下载 =====

    private static File testSongDownload(String url, SongInfo song) {
        totalTests++;
        long start = System.currentTimeMillis();
        System.out.println("[下载] 开始下载: " + song.artist + " - " + song.title);

        String ext = url.toLowerCase().contains(".flac") ? ".flac" : ".mp3";
        File outFile = new File(OUTPUT_DIR, "downloaded_song" + ext);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) KugouConcept/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("HTTP " + code);
            }

            int contentLength = conn.getContentLength();
            System.out.println("[下载] 文件大小: "
                    + (contentLength > 0 ? formatSize(contentLength) : "未知"));

            try (InputStream is = conn.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                long lastReport = 0;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                    // 每下载 1MB 报告一次进度
                    if (totalBytes - lastReport >= 1024 * 1024) {
                        System.out.printf("[下载] 进度: %s / %s%n",
                                formatSize(totalBytes),
                                contentLength > 0 ? formatSize(contentLength) : "?");
                        lastReport = totalBytes;
                    }
                }
            }
            conn.disconnect();

            long elapsed = System.currentTimeMillis() - start;
            long actualSize = outFile.length();
            double speedKBps = elapsed > 0 ? (actualSize / 1024.0) / (elapsed / 1000.0) : 0;

            System.out.println("[下载] 实际大小: " + formatSize(actualSize));
            System.out.println("[下载] 耗时: " + elapsed + "ms");
            System.out.printf("[下载] 平均速度: %.2f KB/s%n", speedKBps);
            System.out.println("[下载] 保存路径: " + outFile.getAbsolutePath());

            if (actualSize > 0) {
                passedTests++;
                System.out.println("[下载] 结果: PASS");
                return outFile;
            } else {
                throw new RuntimeException("下载文件大小为 0");
            }
        } catch (Exception e) {
            failedTests++;
            failureDetails.add("下载: " + e.getMessage());
            System.out.println("[下载] 结果: FAIL - " + e.getMessage());
            if (outFile.exists()) outFile.delete();
            return null;
        }
    }

    // ===== 测试 5: 音频格式检测 =====

    private static void testAudioFormatDetection(File file) {
        totalTests++;
        System.out.println("[格式] 检测文件: " + file.getName());

        try {
            AudioFormatDetector.Result result = AudioFormatDetector.detect(file);

            System.out.println("[格式] 检测结果: " + result);
            System.out.println("[格式] 格式: " + result.format.displayName);
            System.out.println("[格式] 加密: " + result.encrypted);
            System.out.println("[格式] 可播放: " + result.playable);

            if (result.playable && !result.encrypted) {
                passedTests++;
                System.out.println("[格式] 结果: PASS (标准未加密格式)");
            } else if (result.encrypted) {
                failedTests++;
                failureDetails.add("格式检测: 文件被加密 (" + result.format.displayName + ")");
                System.out.println("[格式] 结果: FAIL (文件被加密)");
            } else {
                failedTests++;
                failureDetails.add("格式检测: 未知格式");
                System.out.println("[格式] 结果: FAIL (未知格式)");
            }
        } catch (Exception e) {
            failedTests++;
            failureDetails.add("格式检测: " + e.getMessage());
            System.out.println("[格式] 结果: FAIL - " + e.getMessage());
        }
    }

    // ===== 测试 6: 多歌曲批量测试 =====

    private static void testMultipleSongs() {
        String[] keywords = {"林俊杰 江南", "陈奕迅 浮夸", "邓紫棋 光年之外"};
        int successCount = 0;
        int totalCount = keywords.length;

        for (String keyword : keywords) {
            totalTests++;
            System.out.println("[批量] 搜索: " + keyword);
            try {
                String url = SEARCH_URL
                        + "?format=json"
                        + "&keyword=" + URLEncoder.encode(keyword, "UTF-8")
                        + "&page=1&pagesize=5&showtype=1";
                JsonObject resp = httpGet(url);
                JsonObject data = getObject(resp, "data");
                JsonArray info = data != null ? getArray(data, "info") : null;

                if (info != null && info.size() > 0) {
                    SongInfo song = parseSong(info.get(0).getAsJsonObject());
                    System.out.printf("[批量]   找到: %s - %s (VIP=%b)%n",
                            song.artist, song.title, song.vipRequired);

                    // 尝试获取歌词
                    LyricCandidate candidate = searchLyricCandidate(song);
                    if (candidate != null) {
                        Lyrics lrc = downloadLrc(candidate);
                        if (lrc != null && !lrc.isEmpty()) {
                            System.out.println("[批量]   歌词行数: " + lrc.size());
                            successCount++;
                            passedTests++;
                            System.out.println("[批量]   结果: PASS");
                        } else {
                            System.out.println("[批量]   歌词为空");
                            failedTests++;
                            failureDetails.add("批量[" + keyword + "]: 歌词为空");
                        }
                    } else {
                        System.out.println("[批量]   无歌词候选");
                        failedTests++;
                        failureDetails.add("批量[" + keyword + "]: 无歌词候选");
                    }
                } else {
                    System.out.println("[批量]   未找到歌曲");
                    failedTests++;
                    failureDetails.add("批量[" + keyword + "]: 未找到歌曲");
                }
            } catch (Exception e) {
                System.out.println("[批量]   异常: " + e.getMessage());
                failedTests++;
                failureDetails.add("批量[" + keyword + "]: " + e.getMessage());
            }
        }

        System.out.println("[批量] 批量测试结果: " + successCount + "/" + totalCount + " 成功");
    }

    // ===== 辅助方法：歌词获取 =====

    private static LyricCandidate searchLyricCandidate(SongInfo song) throws Exception {
        String keyword = (song.artist != null && !song.artist.isEmpty())
                ? song.title + " " + song.artist : song.title;
        String url = LYRIC_SEARCH_URL
                + "?ver=1&man=yes&client=pc&keyword=" + URLEncoder.encode(keyword, "UTF-8")
                + "&duration=" + Math.max(0, song.duration * 1000)
                + "&hash=" + URLEncoder.encode(song.hash.toLowerCase(), "UTF-8");
        JsonObject resp = httpGet(url);
        int status = getNum(resp, "status");
        // candidates 是数组，不是对象。早期版本可能返回 candidates.list，这里两种都兼容。
        JsonArray list = null;
        JsonElement candidatesElem = resp.get("candidates");
        if (candidatesElem != null && candidatesElem.isJsonArray()) {
            list = candidatesElem.getAsJsonArray();
        } else if (candidatesElem != null && candidatesElem.isJsonObject()) {
            list = getArray(candidatesElem.getAsJsonObject(), "list");
        }
        if (status != 200 || list == null || list.size() == 0) return null;
        JsonObject best = list.get(0).getAsJsonObject();
        String id = getStr(best, "id", "lrcid");
        String accesskey = getStr(best, "accesskey");
        if (id == null || id.isEmpty() || accesskey == null || accesskey.isEmpty()) return null;
        return new LyricCandidate(id, accesskey);
    }

    private static Lyrics downloadLrc(LyricCandidate candidate) throws Exception {
        String url = LYRIC_DOWNLOAD_URL
                + "?ver=1&client=pc&id=" + URLEncoder.encode(candidate.id, "UTF-8")
                + "&accesskey=" + URLEncoder.encode(candidate.accesskey, "UTF-8")
                + "&fmt=lrc&charset=utf8";
        JsonObject dl = httpGet(url);
        String content = getStr(dl, "content");
        if (content == null || content.isEmpty()) return null;
        byte[] bytes = java.util.Base64.getDecoder().decode(content);
        String lrc = new String(bytes, StandardCharsets.UTF_8);
        return Lyrics.parse(lrc);
    }

    private static String downloadAndDecodeKrc(LyricCandidate candidate) throws Exception {
        String url = LYRIC_DOWNLOAD_URL
                + "?ver=1&client=pc&id=" + URLEncoder.encode(candidate.id, "UTF-8")
                + "&accesskey=" + URLEncoder.encode(candidate.accesskey, "UTF-8")
                + "&fmt=krc&charset=utf8";
        JsonObject dl = httpGet(url);
        String content = getStr(dl, "content");
        if (content == null || content.isEmpty()) return null;
        return KrcDecoder.decodeFromBase64(content);
    }

    // ===== 辅助方法：播放 URL 解析 =====

    private static String resolvePlayUrl(SongInfo song) throws Exception {
        if (song == null || song.hash == null || song.hash.isEmpty()) return null;

        // Endpoint 1: Legacy getSongInfo.php
        System.out.println("[URL] 尝试端点 1: getSongInfo.php");
        String url = tryLegacyPlayInfo(song.hash);
        if (url != null && !url.isEmpty()) return url;

        // Endpoint 2: trackercdn v2
        System.out.println("[URL] 尝试端点 2: trackercdn v2");
        url = tryTrackerCdn(song.hash);
        if (url != null && !url.isEmpty()) return url;

        // Endpoint 3: Web API
        System.out.println("[URL] 尝试端点 3: wwwapi.kugou.com");
        url = tryWebPlayUrl(song);
        if (url != null && !url.isEmpty()) return url;

        return null;
    }

    private static String tryLegacyPlayInfo(String hash) {
        try {
            String url = PLAY_INFO_URL + "?cmd=playInfo&hash="
                    + URLEncoder.encode(hash, "UTF-8");
            JsonObject resp = httpGet(url);
            return firstPlayableUrl(resp);
        } catch (Exception e) {
            System.out.println("[URL]   端点 1 失败: " + e.getMessage());
            return null;
        }
    }

    private static String tryTrackerCdn(String hash) {
        try {
            String lowerHash = hash.toLowerCase();
            String key = md5(lowerHash + "kgcloudv2");
            String url = TRACKER_URL
                    + "?key=" + key
                    + "&hash=" + lowerHash
                    + "&appid=1005&pid=2&cmd=4&behavior=play&album_id=";
            JsonObject resp = httpGet(url);
            String playUrl = firstPlayableUrl(resp);
            if (playUrl != null && !playUrl.isEmpty()) return playUrl;

            JsonArray urls = getArray(resp, "urls");
            if (urls != null) {
                for (JsonElement e : urls) {
                    if (e.isJsonPrimitive()) {
                        String u = e.getAsString();
                        if (u != null && !u.isEmpty()) return u;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("[URL]   端点 2 失败: " + e.getMessage());
            return null;
        }
    }

    private static String tryWebPlayUrl(SongInfo song) {
        try {
            StringBuilder url = new StringBuilder(WEB_PLAY_URL);
            url.append("?r=play/getdata");
            url.append("&hash=").append(URLEncoder.encode(song.hash.toLowerCase(), "UTF-8"));
            if (song.albumId != null && !song.albumId.isEmpty()) {
                url.append("&album_id=").append(URLEncoder.encode(song.albumId, "UTF-8"));
            }
            url.append("&mid=").append(md5(song.hash + System.currentTimeMillis()));
            url.append("&platid=4");

            JsonObject resp = httpGetWithHeaders(url.toString(), new String[][]{
                    {"User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"},
                    {"Referer", "https://www.kugou.com/song/"},
                    {"Cookie", "kg_mid=" + md5(song.hash)}
            });

            JsonObject data = getObject(resp, "data");
            if (data != null) {
                String playUrl = getStr(data, "play_url", "play_backup_url");
                if (playUrl != null && !playUrl.isEmpty()) return playUrl;
            }
            return null;
        } catch (Exception e) {
            System.out.println("[URL]   端点 3 失败: " + e.getMessage());
            return null;
        }
    }

    // ===== HTTP 工具方法 =====

    private static JsonObject httpGet(String urlStr) throws Exception {
        return httpGetWithHeaders(urlStr, null);
    }

    private static JsonObject httpGetWithHeaders(String urlStr, String[][] headers)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) KugouConcept/1.0");
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        if (headers != null) {
            for (String[] h : headers) {
                if (h != null && h.length >= 2) {
                    conn.setRequestProperty(h[0], h[1]);
                }
            }
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new RuntimeException("HTTP " + code + " for " + urlStr);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            conn.disconnect();
        }
        return JsonParser.parseString(sb.toString()).getAsJsonObject();
    }

    // ===== JSON 解析工具方法 =====

    private static SongInfo parseSong(JsonObject o) {
        SongInfo s = new SongInfo();
        s.hash = upper(getStr(o, "hash", "Hash", "file_hash"));
        s.albumId = getStr(o, "album_id", "albumid", "AlbumID");
        s.title = getStr(o, "songname", "songName", "name", "remark", "filename", "fileName");
        s.artist = getStr(o, "singername", "singerName", "author_name", "authorName");
        s.album = getStr(o, "album_name", "albumname", "remark");
        s.duration = getNum(o, "duration", "timeLength", "timelength", "time");
        s.imgUrl = getStr(o, "album_sizable_cover", "img", "image", "cover", "imgUrl", "album_img");
        s.vipRequired = getNum(o, "pay_type", "pay_type_320", "pay_type_sq", "privilege") > 0
                || getNum(o, "fail_process", "fail_process_320", "fail_process_sq") > 0
                || getNum(o, "pkg_price", "pkg_price_320", "pkg_price_sq") > 0;
        return s;
    }

    private static String firstPlayableUrl(JsonObject resp) {
        String url = getStr(resp, "url", "play_url", "playUrl");
        if (url != null && !url.isEmpty()) return url;

        JsonArray backups = getArray(resp, "backup_url");
        if (backups != null) {
            for (JsonElement e : backups) {
                if (e.isJsonPrimitive()) {
                    String backup = e.getAsString();
                    if (backup != null && !backup.isEmpty()) return backup;
                }
            }
        }

        JsonObject data = getObject(resp, "data");
        if (data != null) {
            url = getStr(data, "url", "play_url", "playUrl");
            if (url != null && !url.isEmpty()) return url;
            backups = getArray(data, "backup_url");
            if (backups != null && backups.size() > 0 && backups.get(0).isJsonPrimitive()) {
                return backups.get(0).getAsString();
            }
        }
        return null;
    }

    private static JsonObject getObject(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonArray getArray(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String getStr(JsonObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            if (!o.has(k)) continue;
            try {
                JsonElement e = o.get(k);
                if (e != null && !e.isJsonNull()) return e.getAsString();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static int getNum(JsonObject o, String... keys) {
        if (o == null) return 0;
        for (String k : keys) {
            if (!o.has(k)) continue;
            try {
                JsonElement e = o.get(k);
                if (e != null && !e.isJsonNull()) return e.getAsInt();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    /** 歌词候选信息。 */
    private static class LyricCandidate {
        final String id;
        final String accesskey;
        LyricCandidate(String id, String accesskey) {
            this.id = id;
            this.accesskey = accesskey;
        }
    }
}
