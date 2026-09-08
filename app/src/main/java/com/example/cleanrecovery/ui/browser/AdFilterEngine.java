package com.example.cleanrecovery.ui.browser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ABP 语法广告过滤引擎（对标 Via simple.txt 引擎：无正则遍历、无 trie）。
 *
 * <p>结构（Via 逆向结论 p302x4.C5425d / p289w4.C5236c / C5235b）：</p>
 * <ul>
 *   <li>网络规则：{@code ||host^} 且无选项的纯 host 规则进精确 host 集合（O(1)）；
 *       其余规则按模式 5 字符 n-gram 分桶进 4 张 HashMap（拦截/例外 × URL键/域键），
 *       查询时对 URL 与 referer host 做有限步滑动探测，再对候选做 glob 全匹配。</li>
 *   <li>元素隐藏：{@code domain##selector} 存 {@code Map<域键,选择器[]>}（""=全局、
 *       "#@#"=例外、"~域名"=排除），查询按 host 祖先链收集并拼成 CSS。</li>
 *   <li>glob 语义：{@code ^}=分隔符（非字母数字/.-%）、{@code *}=任意、
 *       {@code |}=起止锚点、{@code ||}=主机名起点锚点。</li>
 * </ul>
 *
 * <p>线程模型：构建（后台线程）产出不可变 {@link Index}，原子换入；
 * 匹配（WebView IO 线程）只读，无锁。</p>
 */
public final class AdFilterEngine {

    // ===== 资源类型位（对齐 ABP $option）=====
    public static final int T_OTHER = 1 << 4;
    public static final int T_SCRIPT = 1 << 5;
    public static final int T_IMAGE = 1 << 6;
    public static final int T_STYLESHEET = 1 << 7;
    public static final int T_SUBDOCUMENT = 1 << 8;
    public static final int T_DOCUMENT = 1 << 9;
    public static final int T_MEDIA = 1 << 10;
    public static final int T_FONT = 1 << 11;
    public static final int T_POPUP = 1 << 12;
    public static final int T_WEBSOCKET = 1 << 13;
    public static final int T_XHR = 1 << 14;
    private static final int ALL_TYPES = T_OTHER | T_SCRIPT | T_IMAGE | T_STYLESHEET
            | T_SUBDOCUMENT | T_DOCUMENT | T_MEDIA | T_FONT | T_POPUP | T_WEBSOCKET | T_XHR;

    private static final int F_EXCEPTION = 1;
    private static final int F_THIRD_PARTY = 1 << 1;
    private static final int F_FIRST_PARTY = 1 << 2;

    /** 允许保留 :has() 选择器（Chromium 105+ 的 WebView 均支持）。 */
    public static volatile boolean allowHasSelectors = true;

    // 常见二级域后缀，用于根域计算（够用即可，不求全）。
    private static final Set<String> SECOND_LEVEL_SUFFIXES = new HashSet<>(java.util.Arrays.asList(
            "co.uk", "org.uk", "ac.uk", "gov.uk",
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
            "com.hk", "com.tw", "com.au", "com.br", "com.mx",
            "co.jp", "co.kr", "co.in", "co.nz", "com.sg", "com.tr", "com.ar",
            "com.ru", "org.ru", "co.za", "com.sa", "com.eg"));

    // ===== 单条网络规则 =====
    public static final class Filter {
        public final String raw;
        final String pattern;
        final String[] domains;   // domain= 项，"~" 前缀=排除
        final int flags;          // F_*
        final int typeMask;       // 0 = 全部类型
        final boolean hostAnchored;
        final boolean startAnchored;
        final boolean endAnchored;
        final boolean hostOnlyRule; // ||host^ 无任何选项 → 可进 host 精确集合
        private volatile Pattern regex;

        Filter(String raw, String pattern, String[] domains, int flags, int typeMask,
               boolean hostAnchored, boolean startAnchored, boolean endAnchored) {
            this.raw = raw;
            this.pattern = pattern;
            this.domains = domains;
            this.flags = flags;
            this.typeMask = typeMask;
            this.hostAnchored = hostAnchored;
            this.startAnchored = startAnchored;
            this.endAnchored = endAnchored;
            // 尾部 ^ 是主机名结束锚点而非通配；剥掉后再判 glob，
            // 这样 ||host^ 才能进 O(1) host 精确集合（Via 语义）。
            String bare = pattern;
            while (!bare.isEmpty() && bare.charAt(bare.length() - 1) == '^') {
                bare = bare.substring(0, bare.length() - 1);
            }
            boolean hasGlob = bare.indexOf('*') >= 0 || bare.indexOf('^') >= 0;
            this.hostOnlyRule = hostAnchored && !hasGlob && !bare.isEmpty() && domains.length == 0
                    && typeMask == 0 && (flags & (F_THIRD_PARTY | F_FIRST_PARTY)) == 0;
        }

        boolean exception() { return (flags & F_EXCEPTION) != 0; }

        String hostOfHostRule() {
            // ||host^ 或 ||host → 纯 host
            String p = pattern;
            while (p.endsWith("^")) p = p.substring(0, p.length() - 1);
            return p;
        }

        boolean typeOk(int type) {
            return typeMask == 0 || type == 0 || (typeMask & type) != 0;
        }

        boolean partyOk(boolean thirdParty) {
            if ((flags & F_THIRD_PARTY) != 0 && !thirdParty) return false;
            if ((flags & F_FIRST_PARTY) != 0 && thirdParty) return false;
            return true;
        }

        boolean domainOk(String pageHost) {
            if (domains.length == 0) return true;
            boolean hasRequired = false;
            boolean requiredHit = false;
            for (String d : domains) {
                boolean excl = d.startsWith("~");
                String dd = excl ? d.substring(1) : d;
                boolean hit = pageHost != null && !pageHost.isEmpty()
                        && (pageHost.equals(dd) || pageHost.endsWith("." + dd));
                if (excl) {
                    if (hit) return false;
                } else {
                    hasRequired = true;
                    if (hit) requiredHit = true;
                }
            }
            return !hasRequired || requiredHit;
        }

        boolean urlMatches(String url) {
            if (pattern.isEmpty()) return false;
            if (hostOnlyRule) return true; // 已由 host 集合判定
            boolean hasGlob = pattern.indexOf('*') >= 0 || pattern.indexOf('^') >= 0;
            if (!hasGlob) {
                if (hostAnchored) {
                    return containsAtHostBoundary(url, pattern);
                }
                if (startAnchored && endAnchored) return url.equals(pattern);
                if (startAnchored) return url.startsWith(pattern);
                if (endAnchored) return url.endsWith(pattern);
                return url.contains(pattern);
            }
            Pattern re = regex;
            if (re == null) {
                re = compileRegex();
                regex = re;
            }
            return re.matcher(url).find();
        }

        private Pattern compileRegex() {
            StringBuilder sb = new StringBuilder();
            if (hostAnchored) sb.append("(?:^|//|\\.)");
            else if (startAnchored) sb.append("^");
            sb.append(globToRegex(pattern));
            if (endAnchored) sb.append("$");
            try {
                return Pattern.compile(sb.toString());
            } catch (Exception e) {
                return Pattern.compile(Pattern.quote(pattern));
            }
        }

        private static boolean containsAtHostBoundary(String url, String pattern) {
            int idx = url.indexOf(pattern);
            while (idx >= 0) {
                if (idx == 0) return true;
                char c = url.charAt(idx - 1);
                if (c == '.') return true;
                if (c == '/' && idx >= 2 && url.charAt(idx - 2) == '/') return true;
                idx = url.indexOf(pattern, idx + 1);
            }
            return false;
        }

        private static String globToRegex(String pattern) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    sb.append(".*");
                } else if (c == '^') {
                    sb.append("(?:[^a-z0-9_.%\\-]|$)");
                } else {
                    if ("\\.[]{}()+?&|$".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        /** 还原为 ABP 行（stub 页展示）。 */
        public String serialize() {
            StringBuilder sb = new StringBuilder();
            if (exception()) sb.append("@@");
            if (hostAnchored) sb.append("||");
            else if (startAnchored) sb.append('|');
            sb.append(pattern);
            if (endAnchored) sb.append('|');
            List<String> opts = new ArrayList<>();
            if ((flags & F_THIRD_PARTY) != 0) opts.add("third-party");
            if ((flags & F_FIRST_PARTY) != 0) opts.add("~third-party");
            for (String d : domains) opts.add("domain=" + d);
            appendTypeOption(opts, T_SCRIPT, "script");
            appendTypeOption(opts, T_IMAGE, "image");
            appendTypeOption(opts, T_STYLESHEET, "stylesheet");
            appendTypeOption(opts, T_SUBDOCUMENT, "subdocument");
            appendTypeOption(opts, T_DOCUMENT, "document");
            appendTypeOption(opts, T_MEDIA, "media");
            appendTypeOption(opts, T_FONT, "font");
            appendTypeOption(opts, T_POPUP, "popup");
            appendTypeOption(opts, T_WEBSOCKET, "websocket");
            appendTypeOption(opts, T_XHR, "xmlhttprequest");
            appendTypeOption(opts, T_OTHER, "other");
            for (int i = 0; i < opts.size(); i++) {
                sb.append(i == 0 ? '$' : ',').append(opts.get(i));
            }
            return sb.toString();
        }

        private void appendTypeOption(List<String> opts, int bit, String name) {
            if ((typeMask & bit) != 0) opts.add(name);
        }
    }

    // ===== 不可变索引 =====
    public static final class Index {
        static final Index EMPTY = new Index(
                new HashSet<String>(), new HashSet<String>(),
                new HashMap<String, List<Filter>>(), new HashMap<String, List<Filter>>(),
                new HashMap<String, List<Filter>>(), new HashMap<String, List<Filter>>(),
                new ArrayList<Filter>(),
                new HashMap<String, List<String>>(), new HashMap<String, List<String>>());

        final Set<String> blockHosts;
        final Set<String> exceptionHosts;
        final Map<String, List<Filter>> blockUrlNgrams;
        final Map<String, List<Filter>> exceptionUrlNgrams;
        final Map<String, List<Filter>> blockDomainNgrams;
        final Map<String, List<Filter>> exceptionDomainNgrams;
        final List<Filter> shortPatterns;
        final Map<String, List<String>> cosmeticRules;
        final Map<String, List<String>> cosmeticExclusions;

        Index(Set<String> blockHosts, Set<String> exceptionHosts,
              Map<String, List<Filter>> blockUrlNgrams, Map<String, List<Filter>> exceptionUrlNgrams,
              Map<String, List<Filter>> blockDomainNgrams, Map<String, List<Filter>> exceptionDomainNgrams,
              List<Filter> shortPatterns,
              Map<String, List<String>> cosmeticRules, Map<String, List<String>> cosmeticExclusions) {
            this.blockHosts = blockHosts;
            this.exceptionHosts = exceptionHosts;
            this.blockUrlNgrams = blockUrlNgrams;
            this.exceptionUrlNgrams = exceptionUrlNgrams;
            this.blockDomainNgrams = blockDomainNgrams;
            this.exceptionDomainNgrams = exceptionDomainNgrams;
            this.shortPatterns = shortPatterns;
            this.cosmeticRules = cosmeticRules;
            this.cosmeticExclusions = cosmeticExclusions;
        }

        /**
         * 网络匹配：返回命中的拦截规则；例外命中或未命中返回 null。
         *
         * @param url        小写化后的完整 URL
         * @param urlHost    URL host（小写，可为 null）
         * @param pageHost   所属页面 host（小写，可为 null）
         * @param type       资源类型位（0=未知/全部）
         * @param thirdParty 是否第三方
         */
        public Filter matchNetwork(String url, String urlHost, String pageHost, int type, boolean thirdParty) {
            if (isEmpty()) return null;
            // 1) host 精确集合（祖先链）；例外集合无条件放行（对齐 Via）
            String h = urlHost == null ? "" : urlHost;
            boolean hostException = false;
            String hostBlockHit = null;
            if (!h.isEmpty()) {
                String cursor = h;
                while (!cursor.isEmpty()) {
                    if (!hostException && exceptionHosts.contains(cursor)) hostException = true;
                    if (hostBlockHit == null && blockHosts.contains(cursor)) hostBlockHit = cursor;
                    if (hostException && hostBlockHit != null) break;
                    int dot = cursor.indexOf('.');
                    if (dot < 0) break;
                    cursor = cursor.substring(dot + 1);
                }
            }
            if (hostException) return null;
            // 2) 例外优先（必须在 host 集合拦截之前：@@ 例外对 ||host^ 拦截同样生效）
            if (findMatch(exceptionUrlNgrams, exceptionDomainNgrams, shortPatterns, true,
                    url, pageHost, type, thirdParty, pageHost) != null) {
                return null;
            }
            if (hostBlockHit != null) {
                return new Filter("||" + hostBlockHit + "^", hostBlockHit,
                        new String[0], 0, 0, true, false, false);
            }
            // 3) 拦截候选
            return findMatch(blockUrlNgrams, blockDomainNgrams, shortPatterns, false,
                    url, pageHost, type, thirdParty, pageHost);
        }

        private Filter findMatch(Map<String, List<Filter>> urlNgrams, Map<String, List<Filter>> domainNgrams,
                                 List<Filter> shorts, boolean exceptionsOnly,
                                 String url, String pageHost, int type, boolean thirdParty, String domainProbeHost) {
            // 短模式（<5 字符）只能全量查
            for (int i = 0; i < shorts.size(); i++) {
                Filter f = shorts.get(i);
                if (f.exception() != exceptionsOnly) continue;
                if (candidateMatches(f, url, pageHost, type, thirdParty)) return f;
            }
            // URL 滑动探测（限 256 窗口，覆盖绝大多数规则位置）
            int limit = Math.min(url.length() - 4, 256);
            for (int i = 0; i < limit; i++) {
                List<Filter> bucket = urlNgrams.get(url.substring(i, i + 5));
                if (bucket == null) continue;
                for (int j = 0; j < bucket.size(); j++) {
                    Filter f = bucket.get(j);
                    if (f.exception() != exceptionsOnly) continue;
                    if (candidateMatches(f, url, pageHost, type, thirdParty)) return f;
                }
            }
            // 域键探测（页面 host 的 5-gram，用于 domain= 规则；ABP 语义按页面域判定）
            if (!domainProbeHost.isEmpty()) {
                int dlimit = Math.min(domainProbeHost.length() - 4, 64);
                for (int i = 0; i < dlimit; i++) {
                    List<Filter> bucket = domainNgrams.get(domainProbeHost.substring(i, i + 5));
                    if (bucket == null) continue;
                    for (int j = 0; j < bucket.size(); j++) {
                        Filter f = bucket.get(j);
                        if (f.exception() != exceptionsOnly) continue;
                        if (candidateMatches(f, url, pageHost, type, thirdParty)) return f;
                    }
                }
            }
            return null;
        }

        private static boolean candidateMatches(Filter f, String url, String pageHost,
                                                int type, boolean thirdParty) {
            return f.typeOk(type) && f.partyOk(thirdParty) && f.domainOk(pageHost) && f.urlMatches(url);
        }

        public boolean isEmpty() {
            return blockHosts.isEmpty() && exceptionHosts.isEmpty()
                    && blockUrlNgrams.isEmpty() && exceptionUrlNgrams.isEmpty()
                    && blockDomainNgrams.isEmpty() && exceptionDomainNgrams.isEmpty()
                    && shortPatterns.isEmpty() && cosmeticRules.isEmpty();
        }

        public boolean hasNetworkRules() {
            return !blockHosts.isEmpty() || !blockUrlNgrams.isEmpty()
                    || !blockDomainNgrams.isEmpty() || !shortPatterns.isEmpty();
        }

        /** 元素隐藏 CSS：全局规则 + host 祖先链规则，100 个选择器一段。 */
        public String cosmeticCss(String host) {
            if (cosmeticRules.isEmpty()) return "";
            Set<String> selectors = new LinkedHashSetCompat();
            Set<String> excluded = new HashSet<>();
            List<String> global = cosmeticRules.get("");
            if (global != null) selectors.addAll(global);
            List<String> globalExcl = cosmeticExclusions.get("");
            if (globalExcl != null) excluded.addAll(globalExcl);
            if (host != null && !host.isEmpty()) {
                String cursor = host;
                while (true) {
                    List<String> rules = cosmeticRules.get(cursor);
                    if (rules != null) selectors.addAll(rules);
                    List<String> excl = cosmeticExclusions.get(cursor);
                    if (excl != null) excluded.addAll(excl);
                    int dot = cursor.indexOf('.');
                    if (dot < 0) break;
                    cursor = cursor.substring(dot + 1);
                }
            }
            if (selectors.isEmpty()) return "";
            StringBuilder css = new StringBuilder();
            List<String> chunk = new ArrayList<>(100);
            for (String sel : selectors) {
                if (excluded.contains(sel)) continue;
                if (!allowHasSelectors && sel.contains(":has(")) continue;
                chunk.add(sel);
                if (chunk.size() == 100) {
                    appendChunk(css, chunk);
                    chunk.clear();
                }
            }
            if (!chunk.isEmpty()) appendChunk(css, chunk);
            return css.toString();
        }

        private static void appendChunk(StringBuilder css, List<String> chunk) {
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) css.append(',');
                css.append(chunk.get(i));
            }
            css.append("{display:none!important}\n");
        }
    }

    /** 兼容低版本无 Set.of 的简单保序集合。 */
    private static final class LinkedHashSetCompat extends java.util.LinkedHashSet<String> { }

    // ===== 构建 =====
    private volatile Index index = Index.EMPTY;

    public Index current() { return index; }

    public void clear() { index = Index.EMPTY; }

    /** 全量重建：行来源（内置/自定义/订阅）由调用方合并后传入。 */
    public void rebuild(List<String> lines) {
        Builder b = new Builder();
        for (String line : lines) b.addLine(line);
        index = b.build();
    }

    /** 用构建器产物原子换入索引（装载器在后台组装完毕后调用）。 */
    public void swap(Builder b) {
        index = b.build();
    }

    /** 规则行数（含 cosmetic），供设置页显示。 */
    public int ruleCount(Index idx) {
        int n = idx.blockHosts.size();
        for (List<Filter> l : idx.blockUrlNgrams.values()) n += l.size();
        for (List<Filter> l : idx.blockDomainNgrams.values()) n += l.size();
        n += idx.shortPatterns.size();
        for (List<String> l : idx.cosmeticRules.values()) n += l.size();
        return n;
    }

    /** 规则构建器（可后台增量 addLine，最后 build 一次成型）。 */
    public static final class Builder {
        final Set<String> blockHosts = new HashSet<>();
        final Set<String> exceptionHosts = new HashSet<>();
        final List<Filter> urlFilters = new ArrayList<>();       // 无 domain=
        final List<Filter> domainFilters = new ArrayList<>();    // 有 domain=
        final Map<String, List<String>> cosmeticRules = new HashMap<>();
        final Map<String, List<String>> cosmeticExclusions = new HashMap<>();
        int cosmeticCount;

        /** @return true=本行被采纳。 */
        public boolean addLine(String rawLine) {
            if (rawLine == null) return false;
            String line = rawLine.trim();
            while (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1).trim();
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return false;
            // hosts 文件行：0.0.0.0 host / 127.0.0.1 host
            if (!line.startsWith("@@") && !line.startsWith("||") && !line.contains("##") && !line.contains("#@#")) {
                String hostLine = tryParseHostsLine(line);
                if (hostLine != null) {
                    return addNetworkLine(hostLine);
                }
            }
            if (line.contains("#?#")) return false; // AdGuard 过程式选择器，不支持
            // 元素隐藏：#@# 优先（一条行只会是一种）
            int exclIdx = line.indexOf("#@#");
            if (exclIdx >= 0) {
                return addCosmetic(line, exclIdx, 3, true);
            }
            int hideIdx = line.indexOf("##");
            if (hideIdx >= 0) {
                return addCosmetic(line, hideIdx, 2, false);
            }
            return addNetworkLine(line);
        }

        private boolean addCosmetic(String line, int idx, int markLen, boolean exclusion) {
            String domainsPart = line.substring(0, idx);
            String selector = line.substring(idx + markLen).trim();
            if (line.indexOf("#?#") >= 0) return false; // AdGuard 过程式，跳过
            if (selector.isEmpty() || selector.length() > 400) return false;
            for (int i = 0; i < selector.length(); i++) {
                char c = selector.charAt(i);
                if (c == '{' || c == '}' || c == ';' || c == '\\' || c == '\'' || c == '"') return false;
            }
            Map<String, List<String>> target = exclusion ? cosmeticExclusions : cosmeticRules;
            boolean added = false;
            if (domainsPart.trim().isEmpty()) {
                added = addToMap(target, "", selector);
            } else {
                for (String d : domainsPart.split(",")) {
                    d = d.trim().toLowerCase(Locale.ROOT);
                    if (d.isEmpty()) continue;
                    if (d.startsWith("~")) {
                        String dd = d.substring(1);
                        if (dd.isEmpty()) continue;
                        // "~域" 排除：只登记进排除映射（## 行）；#@# 行的 ~域 无对应语义，跳过
                        if (!exclusion) added |= addToMap(cosmeticExclusions, dd, selector);
                        continue;
                    }
                    added |= addToMap(target, d, selector);
                }
            }
            if (added && !exclusion) cosmeticCount++;
            return added;
        }

        private static boolean addToMap(Map<String, List<String>> map, String key, String selector) {
            List<String> list = map.get(key);
            if (list == null) {
                list = new ArrayList<>();
                map.put(key, list);
            }
            if (list.contains(selector)) return false;
            list.add(selector);
            return true;
        }

        private boolean addNetworkLine(String line) {
            Filter f = parseNetworkRule(line);
            if (f == null) return false;
            if (f.hostOnlyRule) {
                String host = f.hostOfHostRule();
                if (host.isEmpty()) return false;
                if (f.exception()) exceptionHosts.add(host);
                else blockHosts.add(host);
                return true;
            }
            if (f.pattern.length() < 5 && f.domains.length == 0) {
                urlFilters.add(f);
                return true;
            }
            if (f.domains.length > 0) {
                domainFilters.add(f);
            } else {
                urlFilters.add(f);
            }
            return true;
        }

        public Index build() {
            Map<String, List<Filter>> blockUrl = new HashMap<>();
            Map<String, List<Filter>> exceptionUrl = new HashMap<>();
            Map<String, List<Filter>> blockDomain = new HashMap<>();
            Map<String, List<Filter>> exceptionDomain = new HashMap<>();
            List<Filter> shorts = new ArrayList<>();
            for (Filter f : urlFilters) {
                if (f.pattern.length() < 5) {
                    shorts.add(f);
                    continue;
                }
                Map<String, List<Filter>> target = f.exception() ? exceptionUrl : blockUrl;
                indexNgrams(target, f.pattern, f);
            }
            for (Filter f : domainFilters) {
                Map<String, List<Filter>> target = f.exception() ? exceptionDomain : blockDomain;
                for (String d : f.domains) {
                    String dd = d.startsWith("~") ? d.substring(1) : d;
                    if (dd.length() >= 5) indexNgrams(target, dd, f);
                }
                // 域规则同时挂模式 5-gram，保证 referer 缺失时也能命中
                if (f.pattern.length() >= 5) {
                    Map<String, List<Filter>> urlTarget = f.exception() ? exceptionUrl : blockUrl;
                    indexNgrams(urlTarget, f.pattern, f);
                } else {
                    shorts.add(f); // 短模式进不了 5-gram 桶，全量扫描兜底
                }
            }
            return new Index(blockHosts, exceptionHosts, blockUrl, exceptionUrl,
                    blockDomain, exceptionDomain, shorts, cosmeticRules, cosmeticExclusions);
        }

        private static void indexNgrams(Map<String, List<Filter>> map, String text, Filter f) {
            for (int i = 0; i + 5 <= text.length(); i++) {
                String gram = text.substring(i, i + 5);
                List<Filter> list = map.get(gram);
                if (list == null) {
                    list = new ArrayList<>(1);
                    map.put(gram, list);
                }
                if (!list.contains(f)) list.add(f);
            }
        }
    }

    // ===== 解析 =====
    static Filter parseNetworkRule(String raw) {
        String line = raw.trim().toLowerCase(Locale.ROOT);
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return null;
        boolean exception = line.startsWith("@@");
        if (exception) line = line.substring(2).trim();
        String options = null;
        int dollar = line.indexOf('$');
        if (dollar >= 0) {
            options = line.substring(dollar + 1);
            line = line.substring(0, dollar).trim();
        }
        int flags = exception ? F_EXCEPTION : 0;
        int typeMask = 0;
        boolean unsupportedType = false;
        List<String> domains = new ArrayList<>();
        if (options != null && !options.isEmpty()) {
            for (String optRaw : options.split(",")) {
                String opt = optRaw.trim();
                if (opt.isEmpty()) continue;
                switch (opt) {
                    case "script": typeMask |= T_SCRIPT; break;
                    case "image": typeMask |= T_IMAGE; break;
                    case "stylesheet": case "css": typeMask |= T_STYLESHEET; break;
                    case "subdocument": case "iframe": typeMask |= T_SUBDOCUMENT; break;
                    case "document": typeMask |= T_DOCUMENT; break;
                    case "media": case "video": case "audio": typeMask |= T_MEDIA; break;
                    case "font": typeMask |= T_FONT; break;
                    case "popup": typeMask |= T_POPUP; break;
                    case "websocket": typeMask |= T_WEBSOCKET; break;
                    case "xmlhttprequest": case "xhr": typeMask |= T_XHR; break;
                    case "other": typeMask |= T_OTHER; break;
                    case "object": case "object-subrequest": case "webrtc": case "ping":
                    case "dtd": case "xbl":
                        unsupportedType = true; break;
                    case "~script": typeMask = withoutType(typeMask, T_SCRIPT); break;
                    case "~image": typeMask = withoutType(typeMask, T_IMAGE); break;
                    case "~stylesheet": case "~css": typeMask = withoutType(typeMask, T_STYLESHEET); break;
                    case "~subdocument": case "~iframe": typeMask = withoutType(typeMask, T_SUBDOCUMENT); break;
                    case "~document": typeMask = withoutType(typeMask, T_DOCUMENT); break;
                    case "~media": case "~video": case "~audio": typeMask = withoutType(typeMask, T_MEDIA); break;
                    case "~font": typeMask = withoutType(typeMask, T_FONT); break;
                    case "~popup": typeMask = withoutType(typeMask, T_POPUP); break;
                    case "~websocket": typeMask = withoutType(typeMask, T_WEBSOCKET); break;
                    case "~xmlhttprequest": case "~xhr": typeMask = withoutType(typeMask, T_XHR); break;
                    case "~other": typeMask = withoutType(typeMask, T_OTHER); break;
                    case "third-party": case "3p": flags |= F_THIRD_PARTY; break;
                    case "~third-party": case "~3p": flags |= F_FIRST_PARTY; break;
                    case "all": typeMask = 0; break;
                    case "match-case": case "collapse": case "~collapse": case "background": case "~background":
                        break;
                    case "genericblock": case "generichide": case "elemhide": case "csp":
                        unsupportedType = true; break;
                    default:
                        if (opt.startsWith("domain=")) {
                            addDomainList(domains, opt.substring(7), false);
                        } else if (opt.startsWith("~domain=")) {
                            addDomainList(domains, opt.substring(8), true);
                        } else {
                            return null; // 未知选项 → 丢弃（永不过匹配，等效）
                        }
                }
            }
        }
        // Via 会丢弃只有 object/generichide 等 WebView 无法表达的规则；若同一规则还
        // 指定了 script/image 等可表达类型，则保留可执行的类型部分。
        if (unsupportedType && typeMask == 0) return null;
        boolean hostAnchored = false;
        boolean startAnchored = false;
        boolean endAnchored = false;
        String pattern = line;
        if (pattern.startsWith("||")) {
            hostAnchored = true;
            pattern = pattern.substring(2);
        } else if (pattern.startsWith("|")) {
            startAnchored = true;
            pattern = pattern.substring(1);
        }
        if (pattern.endsWith("|") && pattern.length() > 1) {
            endAnchored = true;
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        pattern = pattern.trim();
        if (pattern.isEmpty()) return null;
        return new Filter(raw.trim(), pattern, domains.toArray(new String[0]), flags, typeMask,
                hostAnchored, startAnchored, endAnchored);
    }

    private static int withoutType(int current, int excluded) {
        if (current == 0) current = ALL_TYPES;
        return current & ~excluded;
    }

    private static void addDomainList(List<String> domains, String spec, boolean forceExclusion) {
        if (spec == null || spec.isEmpty()) return;
        for (String d : spec.split("\\|")) {
            d = d.trim().toLowerCase(Locale.ROOT);
            if (d.isEmpty()) continue;
            if (forceExclusion && !d.startsWith("~")) d = "~" + d;
            domains.add(d);
        }
    }

    /** hosts 文件行 → ABP 形式；非 hosts 行返回 null。 */
    private static String tryParseHostsLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        int space = lower.indexOf(' ');
        if (space <= 0) return null;
        String ip = lower.substring(0, space);
        if (!ip.equals("0.0.0.0") && !ip.equals("127.0.0.1")) return null;
        String rest = lower.substring(space).trim();
        int sp = rest.indexOf(' ');
        String host = sp > 0 ? rest.substring(0, sp) : rest;
        if (host.isEmpty() || host.equals("localhost") || host.indexOf('.') < 0) return null;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            if (!ok) return null;
        }
        return "||" + host + "^";
    }

    // ===== 辅助 =====

    /**
     * 根域（可注册域）。公开后缀判定：Via TLD 完美哈希表（1594 项，含 co.uk 等
     * 二级公开后缀）+ 常见二级后缀兜底表取并集，取「最长已知后缀」为公开后缀，
     * 其上一级标签即根域——等价 PSL registrable domain，替换旧的单表猜测。
     */
    public static String rootDomain(String host) {
        if (host == null || host.isEmpty()) return "";
        String h = host;
        while (h.startsWith(".")) h = h.substring(1);
        if (h.indexOf('.') < 0) return h;
        String[] parts = h.split("\\.");
        int start = parts.length - 1;
        int min = Math.max(0, parts.length - 6);
        for (int i = min; i < parts.length - 1; i++) {
            if (isPublicSuffix(joinLabels(parts, i))) {
                start = i;
                break;
            }
        }
        if (start == 0) return h; // 整个 host 本身是公开后缀
        return joinLabels(parts, start - 1);
    }

    private static boolean isPublicSuffix(String suffix) {
        return ViaTldHashes.isKnownTld(suffix) || SECOND_LEVEL_SUFFIXES.contains(suffix);
    }

    private static String joinLabels(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** URL host 与页面 host 是否同根域。 */
    public static boolean sameSite(String hostA, String hostB) {
        if (hostA == null || hostB == null || hostA.isEmpty() || hostB.isEmpty()) return true;
        String a = hostA.toLowerCase(Locale.ROOT);
        String b = hostB.toLowerCase(Locale.ROOT);
        if (a.equals(b)) return true;
        return rootDomain(a).equals(rootDomain(b));
    }

    /** 是否第三方请求（页面 host 未知时按第一方处理，避免 $third-party 误杀）。 */
    public static boolean isThirdParty(String urlHost, String pageHost) {
        if (pageHost == null || pageHost.isEmpty()) return false;
        if (urlHost == null || urlHost.isEmpty()) return false;
        return !sameSite(urlHost, pageHost);
    }

    /** 从请求特征推断资源类型位（无正则）。 */
    public static int resourceType(boolean mainFrame, Map<String, String> headers, String url) {
        if (mainFrame) return T_DOCUMENT;
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int pathDot = lower.lastIndexOf('.');
        int pathEnd = lower.length();
        int query = lower.indexOf('?');
        if (query >= 0) pathEnd = Math.min(pathEnd, query);
        int hash = lower.indexOf('#');
        if (hash >= 0) pathEnd = Math.min(pathEnd, hash);
        String ext = (pathDot > 0 && pathDot < pathEnd) ? lower.substring(pathDot + 1, pathEnd) : "";
        if (headers != null) {
            // 请求头键值大小写不敏感（WebView 各版本规范化行为不一致）
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String name = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
                String value = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
                if (name.equals("x-requested-with") && value.contains("xmlhttprequest")) return T_XHR;
                if (name.equals("accept")) {
                    if (value.contains("text/css")) return T_STYLESHEET;
                    if (value.contains("text/html")) return T_SUBDOCUMENT;
                    if (value.contains("image/")) return T_IMAGE;
                    if (value.contains("video/") || value.contains("audio/")) return T_MEDIA;
                }
            }
        }
        switch (ext) {
            case "js": case "mjs": case "json": return T_SCRIPT;
            case "css": return T_STYLESHEET;
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "svg":
            case "ico": case "bmp": case "avif": return T_IMAGE;
            case "mp4": case "webm": case "mkv": case "flv": case "m3u8": case "ts":
            case "m4s": case "mpd": case "mp3": case "aac": case "m4a": case "ogg": case "wav": case "flac": return T_MEDIA;
            case "woff": case "woff2": case "ttf": case "otf": case "eot": return T_FONT;
            case "html": case "htm": return T_SUBDOCUMENT;
            default: return T_OTHER;
        }
    }
}
