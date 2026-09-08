package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 广告规则订阅管理（对齐 Via：subscribed.txt 索引 + 每订阅一文件 + 定时更新 + 镜像回退）。
 *
 * <p>存储：{@code files/adblock/subscribed.txt}（每行一个订阅 JSON）
 * 与 {@code files/adblock/sub_<md5(url)>.txt}（ABP 规则正文）。</p>
 */
public final class AdSubscriptionManager {

    /** Via 中文环境展示的订阅目录，顺序、名称和源地址均与 Via 一致。 */
    public static List<CatalogItem> catalog() {
        List<CatalogItem> out = new ArrayList<>();
        out.add(new CatalogItem("EasyList",
                "https://easylist-downloads.adblockplus.org/easylist.txt"));
        out.add(new CatalogItem("EasyList China",
                "https://easylist-downloads.adblockplus.org/easylistchina.txt"));
        out.add(new CatalogItem("CJX's Annoyance List",
                "https://fastly.jsdelivr.net/gh/cjx82630/cjxlist/cjx-annoyance.txt"));
        out.add(new CatalogItem("EasyPrivacy",
                "https://easylist-downloads.adblockplus.org/easyprivacy.txt"));
        out.add(new CatalogItem("Adblock Warning Removal List",
                "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt"));
        return out;
    }

    /** 旧版本曾使用 GitHub 原始地址；读取时迁移到 Via 的规范地址。 */
    private static String canonicalUrl(String url) {
        if ("https://raw.githubusercontent.com/easylist/easylist/master/easylist.txt".equals(url))
            return "https://easylist-downloads.adblockplus.org/easylist.txt";
        if ("https://raw.githubusercontent.com/easylist/easylistchina/master/easylistchina.txt".equals(url))
            return "https://easylist-downloads.adblockplus.org/easylistchina.txt";
        if ("https://raw.githubusercontent.com/easylist/easylist/master/easyprivacy.txt".equals(url))
            return "https://easylist-downloads.adblockplus.org/easyprivacy.txt";
        if ("https://raw.githubusercontent.com/cjx82630/cjxlist/master/cjx-annoyance.txt".equals(url))
            return "https://fastly.jsdelivr.net/gh/cjx82630/cjxlist/cjx-annoyance.txt";
        return url;
    }

    public static final class CatalogItem {
        public final String title;
        public final String url;

        public CatalogItem(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    /** 单条订阅（JSON 持久化）。 */
    public static final class Subscription {
        public String title = "";
        public String url = "";
        public String homepage = "";
        public boolean enabled = true;
        public long lastUpdateMs;
        public int lineCount;
        /** 条件更新凭据：上次响应的 ETag / Last-Modified（任一可用即发条件请求）。 */
        public String etag = "";
        public String lastModified = "";

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("url", url);
            o.put("homepage", homepage);
            o.put("enabled", enabled);
            o.put("lastUpdateMs", lastUpdateMs);
            o.put("lineCount", lineCount);
            o.put("etag", etag);
            o.put("lastModified", lastModified);
            return o;
        }

        static Subscription fromJson(JSONObject o) {
            Subscription s = new Subscription();
            s.title = o.optString("title");
            s.url = o.optString("url");
            s.homepage = o.optString("homepage");
            s.enabled = o.optBoolean("enabled", true);
            s.lastUpdateMs = o.optLong("lastUpdateMs");
            s.lineCount = o.optInt("lineCount");
            s.etag = o.optString("etag");
            s.lastModified = o.optString("lastModified");
            return s;
        }
    }

    /** 下载结果。 */
    public static final class DownloadResult {
        public boolean ok;
        /** 304：内容未变，本地规则仍是最新，无需重建索引。 */
        public boolean notModified;
        public String title = "";
        public int lineCount;
        public String error = "";
        public String body = "";
        /** 响应凭据（落盘到 Subscription，供下次条件请求）。 */
        public String etag = "";
        public String lastModified = "";
    }

    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** 规则正文上限（EasyList 全量约 4MB，12MB 足够并防御异常源撑爆内存）。 */
    static final int MAX_BODY_BYTES = 12 * 1024 * 1024;
    /** 合法规则正文最小字节数。 */
    static final int MIN_BODY_BYTES = 256;
    /** 合法规则正文最少规则行数（防御返回错误页/截断内容覆盖好规则）。 */
    static final int MIN_RULE_LINES = 20;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    // ===== 存储 =====

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "adblock");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File indexFile(Context ctx) {
        return new File(dir(ctx), "subscribed.txt");
    }

    private static File ruleFile(Context ctx, String url) {
        return new File(dir(ctx), "sub_" + md5(url) + ".txt");
    }

    /** Via 初次打开订阅页时按当前语言创建推荐订阅，等待用户逐项启用或手动更新。 */
    private static List<Subscription> defaultSubscriptions() {
        List<Subscription> out = new ArrayList<>();
        for (CatalogItem item : catalog()) {
            Subscription sub = new Subscription();
            sub.title = item.title;
            sub.url = item.url;
            sub.enabled = false;
            out.add(sub);
        }
        return out;
    }

    public static synchronized List<Subscription> load(Context ctx) {
        List<Subscription> out = new ArrayList<>();
        File f = indexFile(ctx);
        if (!f.exists()) {
            out.addAll(defaultSubscriptions());
            save(ctx, out);
            return out;
        }
        boolean migrated = false;
        try {
            String text = readFile(f);
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Subscription s = Subscription.fromJson(new JSONObject(line));
                    String canonical = canonicalUrl(s.url);
                    if (!canonical.equals(s.url)) {
                        File oldRules = ruleFile(ctx, s.url);
                        File newRules = ruleFile(ctx, canonical);
                        if (oldRules.exists() && !newRules.exists()) oldRules.renameTo(newRules);
                        s.url = canonical;
                        migrated = true;
                    }
                    out.add(s);
                } catch (JSONException ignored) {
                }
            }
            if (migrated) writeIndex(f, out);
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void save(Context ctx, List<Subscription> subs) {
        try {
            writeIndex(indexFile(ctx), subs);
        } catch (IOException ignored) {
        }
    }

    private static void writeIndex(File file, List<Subscription> subs) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Subscription s : subs) {
            try {
                sb.append(s.toJson().toString()).append('\n');
            } catch (JSONException ignored) {
            }
        }
        writeFile(file, sb.toString());
    }

    public static synchronized Subscription find(List<Subscription> subs, String url) {
        for (Subscription s : subs) {
            if (s.url.equals(url)) return s;
        }
        return null;
    }

    public static synchronized void remove(Context ctx, String url) {
        List<Subscription> subs = load(ctx);
        Subscription target = find(subs, url);
        if (target == null) return;
        subs.remove(target);
        save(ctx, subs);
        File f = ruleFile(ctx, url);
        if (f.exists()) f.delete();
    }

    public static synchronized void setEnabled(Context ctx, String url, boolean enabled) {
        List<Subscription> subs = load(ctx);
        Subscription s = find(subs, url);
        if (s == null) return;
        s.enabled = enabled;
        save(ctx, subs);
    }

    /** Via 的“编辑”只替换订阅地址，并保留已下载的规则文件和开关状态。 */
    public static synchronized boolean replaceUrl(Context ctx, String oldUrl, String newUrl) {
        String normalized = newUrl == null ? "" : newUrl.trim();
        if (!(normalized.startsWith("http://") || normalized.startsWith("https://"))) return false;
        List<Subscription> subs = load(ctx);
        Subscription target = find(subs, oldUrl);
        if (target == null) return false;
        Subscription duplicate = find(subs, normalized);
        if (duplicate != null && duplicate != target) return false;
        if (oldUrl.equals(normalized)) return true;
        File oldRules = ruleFile(ctx, oldUrl);
        File newRules = ruleFile(ctx, normalized);
        if (oldRules.exists() && !newRules.exists() && !oldRules.renameTo(newRules)) return false;
        target.url = normalized;
        save(ctx, subs);
        return true;
    }

    /**
     * 下载并落盘一条订阅（成功后加入/更新索引）。
     *
     * <p>链路：条件请求（ETag/Last-Modified）→ 主源失败走镜像链；
     * 正文先过校验（大小/规则行数/特征），校验不过保留旧文件不换入。</p>
     *
     * @param existing 已存在条目（更新场景），可为 null
     */
    public static DownloadResult download(Context ctx, String url, Subscription existing) {
        DownloadResult res = fetchChain(url, existing);
        if (res.notModified) {
            // 内容未变：仅刷新时间戳，避免无谓重建
            if (existing != null) {
                existing.lastUpdateMs = System.currentTimeMillis();
                List<Subscription> subs = load(ctx);
                Subscription cur = find(subs, url);
                if (cur != null) {
                    cur.lastUpdateMs = existing.lastUpdateMs;
                    save(ctx, subs);
                }
            }
            return res;
        }
        if (res.ok) {
            List<Subscription> subs = load(ctx);
            Subscription s = existing != null ? existing : new Subscription();
            boolean isNew = find(subs, url) == null;
            s.url = url;
            if (!TextUtils.isEmpty(res.title)) s.title = res.title;
            if (TextUtils.isEmpty(s.title)) s.title = url;
            s.enabled = true;
            s.lastUpdateMs = System.currentTimeMillis();
            s.lineCount = res.lineCount;
            s.etag = res.etag;
            s.lastModified = res.lastModified;
            try {
                writeFile(ruleFile(ctx, url), res.body); // 临时文件 + 原子替换，失败保留旧文件
            } catch (IOException e) {
                res.ok = false;
                res.error = "写入失败: " + e.getMessage();
                return res;
            }
            if (isNew) subs.add(s);
            save(ctx, subs);
        }
        return res;
    }

    /** 更新所有启用且超过间隔的订阅；返回是否有变更（需重建索引）。304 不算变更。 */
    public static synchronized boolean updateOutdated(Context ctx, long intervalMs) {
        // Via 将小于一小时视为关闭自动更新；0（“从不”）不能被解释成全部过期。
        if (intervalMs < 3600_000L) return false;
        List<Subscription> subs = load(ctx);
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (Subscription s : subs) {
            if (!s.enabled) continue;
            if (now - s.lastUpdateMs < intervalMs) continue;
            DownloadResult res = download(ctx, s.url, s);
            if (res.ok && !res.notModified) changed = true;
            // 304：download 内已刷新 lastUpdateMs 并落盘，无需重建索引
        }
        return changed;
    }

    /** 主源 + 镜像链顺序尝试；任一成功即返回。 */
    private static DownloadResult fetchChain(String url, Subscription existing) {
        String etag = existing != null ? existing.etag : "";
        String lastModified = existing != null ? existing.lastModified : "";
        DownloadResult res = fetch(url, etag, lastModified);
        if (!res.ok && !res.notModified) {
            for (String mirror : mirrorsOf(url)) {
                res = fetch(mirror, etag, lastModified);
                if (res.ok || res.notModified) break;
            }
        }
        return res;
    }

    // ===== 下载实现 =====

    private static DownloadResult fetch(String url, String etag, String lastModified) {
        DownloadResult res = new DownloadResult();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/plain, */*");
            if (etag != null && !etag.isEmpty()) conn.setRequestProperty("If-None-Match", etag);
            if (lastModified != null && !lastModified.isEmpty()) {
                conn.setRequestProperty("If-Modified-Since", lastModified);
            }
            int code = conn.getResponseCode();
            if (code == 304) {
                res.notModified = true;
                res.ok = true;
                return res;
            }
            if (code != 200) {
                res.error = "HTTP " + code;
                return res;
            }
            res.etag = conn.getHeaderField("ETag");
            res.lastModified = conn.getHeaderField("Last-Modified");
            String contentType = conn.getContentType();
            if (contentType != null && contentType.contains("text/html")) {
                res.error = "返回了 HTML 而非规则文本";
                return res;
            }
            // 有界读取：防御异常源撑爆内存
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > MAX_BODY_BYTES) {
                    res.error = "规则文件超过 " + (MAX_BODY_BYTES / 1024 / 1024) + "MB 上限";
                    return res;
                }
                bos.write(buf, 0, n);
            }
            in.close();
            String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            String invalid = validateRuleBody(body);
            if (invalid != null) {
                res.error = invalid;
                return res;
            }
            res.title = parseTitle(body);
            res.body = body;
            res.lineCount = countRuleLines(body);
            res.ok = true;
            return res;
        } catch (Exception e) {
            res.error = "NET: " + e.getMessage();
            return res;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 规则正文校验（纯函数，JVM 可测）。返回 null=合法，否则给出原因。
     * 校验不过不换入，本地旧规则（last-known-good）继续生效。
     * 接受两种形态：ABP 规则（||/## 特征）或 hosts 格式（0.0.0.0/127.0.0.1 域名行）。
     */
    static String validateRuleBody(String body) {
        if (body == null || body.trim().isEmpty()) return "内容为空";
        if (body.length() < MIN_BODY_BYTES) return "内容过短（" + body.length() + "B）";
        String head = body.substring(0, Math.min(body.length(), 512)).toLowerCase(Locale.ROOT);
        if (head.contains("<!doctype html") || head.contains("<html")) {
            return "内容是 HTML 而非规则列表";
        }
        boolean looksLikeAdblock = body.contains("||") || body.contains("##")
                || body.contains("#@#") || body.contains("#?#");
        if (!looksLikeAdblock && countHostsLines(body) < MIN_RULE_LINES) {
            return "内容不含 ABP 规则特征";
        }
        if (countRuleLines(body) < MIN_RULE_LINES) return "规则行数不足 " + MIN_RULE_LINES;
        return null;
    }

    /** hosts 格式行数（0.0.0.0 domain / 127.0.0.1 domain，含缩进与 tab 分隔）。 */
    static int countHostsLines(String body) {
        int count = 0;
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("0.0.0.0") || t.startsWith("127.0.0.1")) count++;
            if (count >= MIN_RULE_LINES) break;
        }
        return count;
    }

    /** 从规则头 20 行解析 ! Title: 元数据。 */
    private static String parseTitle(String body) {
        String[] lines = body.split("\n");
        int max = Math.min(lines.length, 20);
        for (int i = 0; i < max; i++) {
            String line = lines[i].trim();
            if (line.startsWith("! Title:")) {
                return line.substring(8).trim();
            }
        }
        return "";
    }

    private static int countRuleLines(String body) {
        int count = 0;
        for (String line : body.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("!") && !line.startsWith("[")) count++;
        }
        return count;
    }

    /** 国内可达性镜像链：raw/github/cdn/unpkg → fastly jsdelivr → statically（多镜像，不只依赖一家）。 */
    static List<String> mirrorsOf(String url) {
        List<String> out = new ArrayList<>();
        String fastly = mirrorOf(url);
        if (fastly != null && !fastly.equals(url)) out.add(fastly);
        if (url != null && url.startsWith("https://raw.githubusercontent.com/")) {
            String rest = url.substring("https://raw.githubusercontent.com/".length());
            out.add("https://cdn.statically.io/gh/" + rest);
        }
        return out;
    }

    /** 单一镜像映射（保留给其他调用点参考）：github/raw/unpkg/jsdelivr → fastly.jsdelivr.net。 */
    static String mirrorOf(String url) {
        if (url == null) return null;
        if (url.startsWith("https://raw.githubusercontent.com/")) {
            String rest = url.substring("https://raw.githubusercontent.com/".length());
            // owner/repo/branch/path... → gh/owner/repo@branch/path...
            int s1 = rest.indexOf('/');
            int s2 = s1 < 0 ? -1 : rest.indexOf('/', s1 + 1);
            int s3 = s2 < 0 ? -1 : rest.indexOf('/', s2 + 1);
            if (s1 > 0 && s2 > s1 && s3 > s2) {
                String owner = rest.substring(0, s1);
                String repo = rest.substring(s1 + 1, s2);
                String branch = rest.substring(s2 + 1, s3);
                String path = rest.substring(s3 + 1);
                return "https://fastly.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch + "/" + path;
            }
            return null;
        }
        if (url.startsWith("https://github.com/")) {
            String rest = url.substring("https://github.com/".length());
            if (rest.contains("/blob/")) {
                return "https://fastly.jsdelivr.net/gh/" + rest.replace("/blob/", "@");
            }
            if (rest.contains("/raw/")) {
                return "https://fastly.jsdelivr.net/gh/" + rest.replace("/raw/", "@");
            }
            return null;
        }
        if (url.startsWith("https://cdn.jsdelivr.net/")) {
            return "https://fastly.jsdelivr.net/" + url.substring("https://cdn.jsdelivr.net/".length());
        }
        if (url.startsWith("https://unpkg.com/")) {
            return "https://fastly.jsdelivr.net/npm/" + url.substring("https://unpkg.com/".length());
        }
        return null;
    }

    // ===== IO 工具 =====

    /** 供规则装载器读取某个订阅的规则行。 */
    public static synchronized List<String> readRuleLines(Context ctx, String url) {
        List<String> out = new ArrayList<>();
        File f = ruleFile(ctx, url);
        if (!f.exists()) return out;
        try {
            String body = readFile(f);
            for (String line : body.split("\n")) out.add(line);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String readFile(File f) throws IOException {
        FileInputStream fin = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fin.read(buf)) > 0) bos.write(buf, 0, n);
        fin.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeFile(File f, String body) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream fout = new FileOutputStream(tmp);
        fout.write(body.getBytes(StandardCharsets.UTF_8));
        fout.close();
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
