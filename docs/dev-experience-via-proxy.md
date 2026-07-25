# 代理部分

> 纯 Java SS 本地代理（FlClash 风格子集）开发经验记录。Part B。

## 一、交付概述

新建包 `com.example.cleanrecovery.proxy`，实现订阅解析 → 节点选择 → 本地 SOCKS5/HTTP 代理 → WebView 走代理的完整链路（不含 VpnService，仅本地代理 + WebView ProxyController）。

### 新增/改动文件清单

**Java（`app/src/main/java/com/example/cleanrecovery/proxy/`）**
- `ProxyNode.java` — 节点模型（name/server/port/cipher/password）。
- `SsCrypto.java` — SS AEAD 加密核心：`EVP_BytesToKey`(MD5) 主密钥派生、`HKDF-SHA1`("ss-subkey") 子密钥派生、AES-256-GCM / ChaCha20-IETF-Poly1305 的 seal/open、nonce 构造（4 字节 0 前缀 + 8 字节小端计数器）。
- `SsEncryptor.java` — 流式加密器：明文流切成 ≤0x3FFF 块，输出 `salt || [len块][data块]...`。
- `SsDecryptor.java` — 流式解密器：状态机跨包拼接，解出明文流。
- `SsAddress.java` — 地址帧编解码（ATYP+ADDR+PORT，IPv4/域名/IPv6）。
- `SsRelay.java` — 单连接双向中继：连 SS server → 发 salt+加密地址帧 → 上行加密/下行解密。
- `Socks5Server.java` — 本地 SOCKS5（127.0.0.1:0 自动选端口，无认证，仅 CONNECT）。
- `HttpProxyServer.java` — 本地 HTTP CONNECT 代理（备选路径）。
- `ProxyEngine.java` — 引擎状态持有者（当前节点/端口），静态 `current()` 供路由与 UI 读取。
- `ProxyService.java` — 前台 Service（dataSync），管生命周期 + 通知栏。
- `ProxyActivity.java` — UI：订阅输入+拉取、节点列表 RecyclerView 单选、启停、状态显示。
- `ProxyNodeAdapter.java` — 节点列表适配器。
- `ProxyPrefs.java` — 订阅 URL/节点列表/选中索引持久化（SharedPreferences + JSON）。
- `ProxyServiceExtras.java` — ProxyNode ↔ JSON 互转（Intent 传递）。
- `ProxyRouter.java` — WebView 路由工具（静态 API，供 BrowserActivity 调用，未改 BrowserActivity）。

**资源**
- `res/layout/activity_proxy.xml`、`res/layout/item_proxy_node.xml`
- `res/values/strings_proxy.xml`（仅新增项，与 `strings_browser.xml` 中已有 `proxy_*` 合并，避免重名）
- `res/drawable/ic_proxy_on.xml`、`res/drawable/ic_proxy_off.xml`（新名，未与已有 `ic_proxy.xml` 冲突）

**Manifest**：追加 `ProxyActivity` + `ProxyService`（`foregroundServiceType=dataSync`），保留所有已有声明。未加 `BIND_VPN_SERVICE`（方案不使用 VpnService）。

**build.gradle**：webkit 依赖已由 Part A 加好（`androidx.webkit:webkit:1.10.0`），本次未新增依赖、未升级任何版本。

**单测（`app/src/test/java/com/example/cleanrecovery/proxy/`）**
- `SubscriptionParserTest.java` — Base64 ss 列表 / 整段 Base64 / Clash YAML / 单 URI / 自动识别。
- `SsCryptoTest.java` — 密钥派生、HKDF、AES-256-GCM 往返、流式加解密往返（含 >16KB 多块）、ChaCha20 往返（环境支持时）。
- `AddressFrameTest.java` — 域名/IPv4 帧编解码、端口大端。
- `Socks5HandshakeTest.java` — 真实 socket SOCKS5 握手回复校验。

**对 Part A 的最小补丁（非业务改动，仅为解除编译/运行阻塞）**
- `BrowserActivity.java`：补一行 `import ...TabSnapshotHolder;`（Part A 遗漏的 import，导致编译失败）。
- `activity_browser.xml`：补一个 `@+id/browser_sniffer_count` 的 TextView（BrowserActivity 引用但布局缺失，导致 `R.id` 找不到）。

## 二、构建与单测真实结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :app:assembleDebug` | **BUILD SUCCESSFUL** |
| `.\gradlew.bat :app:testDebugUnitTest` | **BUILD SUCCESSFUL**（全量，含代理 4 个测试类） |
| `.\gradlew.bat :app:installDebug` | **Installed on CleanDev_API35** |
| `.\gradlew.bat :app:lintDebug` | **BUILD FAILED（既有状态）**：项目大量既有 NewApi 错误（minSdk 23 调 API 24/26，未开 desugaring），分布于 `LyricsView`/`BilibiliExtractor`/`KrcDecoder`/`SmartFormatSelector`/`BackgroundDownloadService`/`BrowserActivity` 等非本任务文件。本任务新增文件已**零 lint error**（仅剩 warning：`NotifyDataSetChanged`/`SetTextI18n`/`AuthLeak(样例)`/`RequiresFeature(已显式 suppress)`）。lint 失败为既有项目问题，非本次引入。 |

## 三、模拟器验证结果（CleanDev_API35，API 35）

1. `adb devices` → `emulator-5554 device`，`sys.boot_completed=1`。
2. `installDebug` 成功。
3. `am start ProxyActivity` → 启动无崩溃，进程存活。
4. UI 全流程（截图确认）：
   - 点「载入样例订阅」→ 订阅框填入样例 `ss://` 列表。
   - 点「拉取订阅」→ `logcat: doFetch parsed=2`，RecyclerView 渲染 2 个节点（SampleLocal `aes-256-gcm@127.0.0.1:8388`、SampleRemote `chacha20-ietf-poly1305@198.51.100.7:8388`）。
   - 选中第一个节点 → 点「启动代理」。
   - `logcat: Socks5Server: SOCKS5 listening on 127.0.0.1:40711` / `HttpProxyServer: HTTP proxy listening on 127.0.0.1:37359` / `ProxyService: engine started`。
   - 截图确认状态卡显示「状态: 代理运行中 · 本地 SOCKS5:127.0.0.1:40711 | HTTP:37359」，状态图标变为 `ic_proxy_on`，底部按钮变为「停止代理」。
5. OS 级监听确认：`/proc/net/tcp6` 出现两条 `0100007F`（127.0.0.1）`LISTEN(0A)` 记录，端口 `8F4B`(40711)/`AB75`(37359)。
6. 真机 SOCKS5 握手（`adb forward` + PowerShell TcpClient）：
   - 握手回复 `05 00`，CONNECT 回复 `05 00 00 01`。
   - `logcat: CONNECT api.ipify.org:443 via SampleLocal (aes-256-gcm@127.0.0.1:8388)`。
   - `SsRelay` 随后尝试连 SS server `127.0.0.1:8388`，因无真实 SS 服务而 `ECONNREFUSED`（符合预期）。
7. WebView 代理支持确认：模拟器 WebView `versionName=124.0.6367.219`（≥108），`WebViewFeature.PROXY_OVERRIDE` 可用，`ProxyController.setProxyOverride` 路径可用。

> 因无真实可用 SS 节点，未验证出口 IP 实际变化；按规格 fallback 已验证：代理服务启动、SOCKS5 握手、SS 加密往返单测、WebView 代理设置不崩溃。

## 四、阻碍与切换记录（3 次失败规则）

1. **`androidx.webkit` ProxyConfig API 误用**：初版用 `addDirect(...)` 与 `socks5://`，查官方文档后修正为 `addBypassRule(...)` + `socks://`（scheme 仅接受 HTTP/HTTPS/SOCKS）。1 次失败即切换，未到 3 次。
2. **`ProxyService` 前台服务超时崩溃**：节点非法分支先 `stopForeground` 再 `stopSelf`，未先 `startForeground`，触发 `ForegroundServiceDidNotStartInTimeException`。修正为「进入 START 分支立即 `startForeground` 占位通知，再做校验/退出」。1 次失败即修复。
3. **ChaCha20-IETF-Poly1305**：JDK 17 单测环境支持 `ChaCha20-Poly1305`，往返通过；但 Android minSdk 23 的旧设备 `javax.crypto` 不提供该算法（API 28+）。代码以 `isSupported` 守卫，运行时不支持则工厂不可用，调用方回退 `aes-256-gcm`。**记为已知限制**（未到 3 次失败，但跨版本兼容性受限）。
4. **`java.util.Base64` NewApi**：初版用 `java.util.Base64`（API 26），lint 报错且会破坏 JVM 单测对 `android.util.Base64` 的依赖。切换为**手写纯 Java Base64 解码器**（兼容标准/URL-safe、忽略空白与 padding），JVM 与 Android 通用，lint error 清零。1 次失败即切换。
5. **UI 自动化坐标漂移**：EditText 载入样例后变高，把按钮从 y≈412 下推到 y≈485，首轮 `input tap` 命中 EditText 而非按钮，导致 `doFetch` 未触发。重新 dump UI 取正确坐标后通过。属测试操作问题，非代码缺陷。

## 五、已知限制

- 仅支持 **SS 协议**；不支持 VMess/Trojan/Hysteria 等。
- AEAD 算法：**aes-256-gcm** 全版本可用；**chacha20-ietf-poly1305** 需 API 28+ 运行环境，旧设备不可用（代码守卫回退）。
- 不支持 SS 旧式流加密（非 AEAD）与 `plugin`（obfs/v2ray-plugin）。
- 本地 HTTP 代理仅实现 `CONNECT` 隧道（HTTPS）；明文 HTTP（GET/POST 转发）未实现，返回 405。WebView 实际流量以 HTTPS CONNECT 为主，影响可忽略。
- 订阅解析为极简手写解析器：Clash YAML 仅取 `proxies:` 段 `type: ss` 的 `name/server/port/cipher/password`，不支持 `plugin-opts`/`obfs`/`ws-opts` 等；Base64 ss 列表支持 SIP002 与整段 Base64。
- 无真实 SS 节点时，出口 IP 不会变化；本地代理服务、握手、加解密仍可独立验证。
- `ProxyRouter` 已实现但**未接入 BrowserActivity**（按硬约束不改 Part A 浏览器文件）；集成由协调者后续完成。

## 六、ProxyRouter 集成 API 说明（供后续 BrowserActivity 菜单接入）

`com.example.cleanrecovery.proxy.ProxyRouter` 为静态工具类，建议在 BrowserActivity 的 WebView 创建/设置后、以及代理启停后调用：

```java
// WebView 创建并 applySettings 之后调用：若引擎运行则路由到本地 SOCKS5，否则清除覆盖
ProxyRouter.applyProxy(webView);

// 代理停止 / 用户关闭代理时调用：清除 WebView 代理覆盖与系统属性
ProxyRouter.clearProxy();

// 查询当前是否有生效的本地代理（用于菜单/地址栏显示代理指示）
boolean on = ProxyRouter.isProxyActive();
```

行为：
- 优先 `ProxyController.setProxyOverride`（`socks://127.0.0.1:<socks5Port>`，bypass 127.0.0.1/localhost），需 `WebViewFeature.PROXY_OVERRIDE`（模拟器 WebView 124 已支持）。
- 连续 3 次失败则切 `System.setProperty("http.proxyHost"/"http.proxyPort"/"https.proxyHost"/"https.proxyPort")` 指向本地 HTTP 代理端口（`ProxyEngine.httpPort()`）。
- 当前本地端口由 `ProxyEngine.current()` 读取（由 `ProxyService` 启停时写入）。

建议接入点（协调者后续做，本次未改 BrowserActivity）：
- 菜单「代理」入口已存在（`menu_proxy` → `ProxyActivity`），返回后调用 `ProxyRouter.applyProxy(currentWebView)`。
- 设置页加「代理」开关；`onResume`/代理启停后刷新。
- 地址栏旁加代理状态指示（`isProxyActive()`）。

## 七、关键实现备忘

- SS AEAD nonce：`nonce[4..12]` 写 8 字节小端计数器，前 4 字节 0；每块计数 +1（len 与 data 各占一次）。
- 子密钥：`HKDF-SHA1(salt, masterKey, "ss-subkey", keyLen)`，SHA1 输出 20 字节，32 字节需 2 轮 expand。
- 主密钥：`EVP_BytesToKey(MD5)`：`D1=MD5(pw)`，`D2=MD5(D1||pw)`，取前 32 字节。
- 地址帧作为明文流前缀送入 `SsEncryptor`，无需单独处理；服务端返回流不含地址帧，`SsDecryptor` 输出即目标数据。
- `ProxyEngine` 用 `AtomicReference<ProxyEngine>` 持有单例引用，Service 启停时 set/clear，避免跨组件直接持有 Service。

## 八、浏览器 Part A 精修与代理接入

> 标题：浏览器 Part A 精修与代理接入。本节记录按 VIA 真实规格对 Part A 浏览器做 1:1 校准、并把已实现的 SS 代理接入 BrowserActivity 的开发经验，以及在模拟器 CleanDev_API35 上跑完整用户点击路径的验证结果。

### 8.1 与 VIA 对齐的点

- **工具栏（顶部模式）**：返回 | 前进 | 地址输入框 | 主页 | 标签(带角标) | 菜单(三点) | 代理状态点。48dp 级高度（	oolbar_height=56dp 内 40dp 图标区），24dp 单色线性 vector 图标（ic_back/ic_home/ic_tabs/ic_more_vert 等，illColor=@color/text_primary）。

- **菜单（38 项核心子集）**：落地 14 项，顺序向 VIA 靠拢——新建标签、关闭标签、刷新、书签、历史、分享、资源嗅探、下载、翻译、夜间模式、桌面/移动 UA、代理、设置、退出。菜单图标用 24dp 单色 vector；通过反射 setOptionalIconsVisible(true) 让 PopupMenu 显示图标（PopupMenu 默认不显示图标）。夜间/UA/代理三项标题动态反映当前状态。

- **主页九宫格**：VIA 风格 sa.d 单例 → 本项目用 BrowserDatabaseHelper 新增 quicklinks 表持久化（DB_VERSION 升到 2，onUpgrade 改为版本感知迁移而非 drop&recreate，保留已有书签/历史）。首屏 4 列 GridLayout + 首字母圆形图标 + 标题；点击加载、长按删除；底部红色「+」添加快捷链接（对话框输入名称+网址，自动补 https://）。首次创建种子 6 个常用站点（百度/Bing/GitHub/YouTube/知乎/微博）。

- **设置分类**：常规项落地——UA 切换(桌面/移动预设)、夜间模式、JS 开关、图片显示、首页自定义、代理入口按钮（打开 ProxyActivity）。BrowserPrefs 持久化。

- **多标签**：角标显示当前标签数；TabsActivity 管理（列表+新建+关闭，复用 TabManager/TabSnapshotHolder）。

- **书签/历史**：BookmarksActivity/HistoryActivity 用 BrowserDatabaseHelper；列表+增删+点击打开+历史清空。

- **配色**：品牌红 #ffd64146（ia_accent）作 accent，深色 #ff232323（ia_dark）。新增 VIA 专属颜色，**不覆盖全局 rand_accent**（避免影响 app 其余模块）；标签角标 g_tab_badge、进度条 progressTint、主页标题、添加按钮 tint 改用 ia_accent。

- **夜间模式**：API 35 上 WebSettings.setForceDark 已 no-op，故改用 ndroidx.webkit.WebSettingsCompat.setForceDark（webkit 1.10.0 已支持），并始终注入 CSS 兜底（html/body 暗色背景 + 文字色 + 链接色），确保可见生效。

- **地址栏智能识别**：保留 http(s):// 直载、含 . 补 https://、否则搜索（YouTube 搜索）。

### 8.2 偏差与原因

- **菜单未全 38 项**：按规格只落地核心子集（14 项），未实现朗读、脚本、广告拦截、网络日志、离线页、以图搜图、AI、同步等——这些需独立子系统，超出 Part A 范围。

- **工具栏仅顶部模式**：VIA 支持上/下/三明治/双行 4 种模式，本项目只实现顶部模式（规格明确要求顶部）。

- **图标用 @color/text_primary 而非 ?attr 前景 tint**：项目主题未定义 VIA 风格 attr（9 等），Activity 用 SystemUiHelper 而非自定义主题；为不引入大范围主题改造，图标直接着色 	ext_primary，夜间模式靠 WebView 暗化而非工具栏图标反色。

- **主页九宫格图标为首字母文字**：VIA 用站点 favicon 彩色图标；本项目不引入网络图标加载（避免新依赖与网络请求），用首字母 + ia_grid_item_bg 圆角块替代，符合「核心交互 1:1，非像素级」。

- **搜索引擎未做切换器**：规格标注「可选」，本次未落地设置页搜索引擎选择，搜索固定走 YouTube。

### 8.3 代理接入做法

- **菜单「代理」**：menu_proxy → startActivityForResult(new Intent(this, ProxyActivity.class), REQ_PROXY)（已存在，保留）。

- **WebView 创建后**：
ewTab 在 pplySettings/ttachClients 之后立即 ProxyRouter.applyProxy(webView)（try/catch 防御）。

- **onResume 重 apply**：从 ProxyActivity 返回后，按 ProxyRouter.isProxyActive() 重新 pplyProxy 或 clearProxy，保证状态一致。

- **代理状态指示**：工具栏菜单按钮右侧加 rowser_proxy_dot（g_proxy_dot 椭圆），updateProxyIndicator() 按 isProxyActive() 切换 ia_proxy_on/ia_proxy_off 颜色与 contentDescription。

- **退出不强制关代理**：onDestroy 不调用 clearProxy；clearProxy 仅在用户主动停代理（ProxyActivity 停止）时由 ProxyRouter 内部触发，允许后台保持。

- **设置页代理入口**：settings_proxy_entry 按钮 → ProxyActivity。

### 8.4 用户点击路径验证结果（模拟器 CleanDev_API35，真实运行）

> 截图存于验证过程，文件名 1_home.png … 19_sniffer.png、inal.png。

1. **进入在线观影 → 浏览器界面**：✅ 通过。m start 拉起 BrowserActivity，首屏显示顶部工具栏 + 九宫格主页（主页 红色标题 + 6 快捷链接 + 红色「+」），标签角标「1」，代理点灰色（1_home.png/inal.png）。

2. **地址栏输入加载、前进/后退/刷新/主页**：✅ 通过。快捷链接点击加载 https://www.baidu.com/（3b.png）、https://www.bing.com/（18_nightbing.png）成功，地址栏回显 URL；菜单「刷新」reload 生效；主页按钮回到九宫格。**注**：db shell input text 注入地址栏被模拟器中文 IME 自动纠错（example.com→皮。一批反应。org），属环境问题（非 app 缺陷）；URL 加载路径（loadUrl）已通过快捷链接点击与 m start --es url 两条路径验证（13_ipify3.png 渲染出 IP 141.11.46.165）。

3. **新建标签、切换、关闭（角标变化）**：✅ 通过。菜单「新建标签」→ 角标 1→2，新标签显示九宫格主页（5_newtab.png，badge=「2」）。

4. **添加书签、打开书签页、打开历史页、清空历史**：✅ 通过。书签页空态「没有书签」→ 添加当前 bing → 列表出现 https://www.bing.com/（m3）；历史页列出 4 条带时间戳记录（h1）→ 清空 → 「没有历史记录」（h3）。

5. **设置：切 UA、开夜间、关 JS、关图片**：✅ 通过。设置页列出全部项 + 代理入口按钮（se1）；勾选夜间、取消 JS、取消图片、保存 → 重开设置确认持久化（se4：夜间=checked、JS=unchecked、图片=unchecked）；夜间模式下 Bing 渲染为深色主题（18_nightbing.png），证明 WebSettingsCompat.setForceDark 在 API 35 生效。

6. **菜单→代理→ProxyActivity→…→返回→指示亮起→访问 ipify 不崩溃**：✅ 通过。完整链路：填订阅（样例）→拉取（解析 2 节点 SampleLocal/SampleRemote）→选节点→启动代理（状态：代理运行中 本地 SOCKS5：127.0.0.1:38561 | HTTP：42079，8）→返回浏览器→代理点变红「代理已开」（9/
1）。logcat 证实 ProxyRouter: ProxyController applied: socks://127.0.0.1:38561，且 Socks5Server: CONNECT api.ipify.org:443 via SampleLocal → SsRelay: relay error: ECONNREFUSED（样例节点无真实 SS server，预期失败）。WebView 显示标准 
et::ERR_CONNECTION_RESET 错误页，**不崩溃**，代理路径生效（15_proxyreload.png）。停止代理：ProxyController cleared，代理点回灰「代理未开」（s2）。

7. **资源嗅探**：✅ 通过。菜单「资源嗅探」打开嗅探面板，显示 Detected media / Play the video to detect more streams. Select one to download.（sn1/19_sniffer.png），原有 MediaSniffer/HiddenMediaSniffer/CompositeWebViewClient 链路保留未破坏。

### 8.5 异常分支覆盖

- **无网络/代理不可达**：ERR_CONNECTION_RESET 由 WebView 标准错误页处理，不崩溃。

- **代理启动失败**：样例节点 127.0.0.1:8388 无 SS server，SsRelay 捕获 ECONNREFUSED 并日志记录、关闭连接，不崩溃。

- **订阅拉取失败/空订阅/非法 URL**：ProxyActivity 对空输入 toast 提示；解析异常 try/catch 回退 toast；地址栏非法输入走搜索分支，不崩溃。

### 8.6 阻碍与切换记录

1. **地址栏 db input text 被中文 IME 纠错**：连续 2 次输入 example.com 均被改成中文（皮。一批反应。org）。切换：① 把 inputType 改为 	extUri|textNoSuggestions；② db shell ime set 切到 LatinIME；③ 改用快捷链接点击与 m start --es url 验证 URL 加载路径。判定：环境（IME）问题，非 app 缺陷，URL 加载路径已由其他两条路径验证通过。

2. **URLEncoder.encode(String, Charset) 触发 lint NewApi（需 API 33）**：lintDebug 报错。切换：新增 urlEncode(String) helper 用 URLEncoder.encode(s, "UTF-8")（2 参 String 版，API 1+ 可用），替换 2 处调用。修复后 BrowserActivity 无 lint Error。

3. **setForceDark 在 API 35 no-op**：夜间模式不生效。切换：改用 ndroidx.webkit.WebSettingsCompat.setForceDark + 始终注入 CSS 兜底，Bing 实测渲染深色，生效。

4. **inputType 误加 	extCapNone**：AAPT 报「incompatible with inputType flags」。切换：去掉 	extCapNone，保留 	extUri|textNoSuggestions，编译通过。

5. **lintDebug 整体 BUILD FAILED**：245 个 NewApi Error 全部来自**非 Part A 范围**的既有文件（LyricsView/BackgroundDownloadService/BilibiliExtractor/CompositeWebViewClient/KrcDecoder/KugouAuthClient/FileBrowserActivity/SmartFormatSelector 等，minSdk 23 调用 API 24+ API）。判定：**既有问题，非本次引入**；Part A 全部文件（BrowserActivity/BookmarksActivity/HistoryActivity/BrowserSettingsActivity/TabsActivity/ui/browser/*）lint 无 Error（仅 1 个 SetTextI18n Warning）。按硬约束「禁止删除用户已有改动、禁止擅自升级」，未触碰这些文件。

### 8.7 构建 / 单测真实结果

- .\gradlew.bat :app:assembleDebug：✅ BUILD SUCCESSFUL。

- .\gradlew.bat :app:testDebugUnitTest：✅ BUILD SUCCESSFUL（未破坏既有单测，含 SS 加解密 / SOCKS5 握手 / 订阅解析单测）。

- .\gradlew.bat :app:installDebug：✅ Installed on CleanDev_API35。

- .\gradlew.bat :app:lintDebug：❌ BUILD FAILED（245 个既有 NewApi Error，非 Part A 文件；Part A 文件无 Error）。

### 8.8 已知限制

- 仅顶部工具栏模式；未实现 VIA 的下/三明治/双行模式。

- 菜单仅 14 项核心子集，未含朗读/脚本/广告拦截/同步/AI 等。

- 主页九宫格图标为首字母文字，非站点 favicon。

- 搜索引擎固定 YouTube，未做切换器。

- 夜间模式依赖 WebSettingsCompat.setForceDark + CSS 注入；对强反色站点可能效果有限。

- 代理出口 IP 仅在真实可用 SS 节点时才会变化；样例节点仅验证链路不崩溃。

- 不支持 VMess/Trojan/Hysteria（继承 Part B 限制）。

## 九、Phase1 UI/功能补全

> 标题：Phase1 UI/功能补全（VIA 对齐补全）。本节记录按 `phase1.md` 对照 VIA 38 项菜单全集补齐 feasible 项、工具栏多模式、搜索引擎可配置、九宫格 favicon、夜间模式跟随的开发经验，以及在模拟器 CleanDev_API35 上的验证结果。

### 9.1 改动清单

**Java**
- `ui/activity/BrowserActivity.java`：
  - 菜单工厂 `onMenuItemClicked` 扩展至 VIA 全集 37 项 + 应用便捷项（新建/关闭标签）+ 应用专属项（截图/代理/退出），共 39 项；feasible 项落地，占位项统一 Toast「开发中」。
  - 新增 feasible 处理：`openWith`（Intent.ACTION_VIEW chooser）、`showFindInPageDialog`（WebView.findAllAsync + setFindListener 计数 + 上一个/下一个）、`captureScreenshot`（WebView.draw(Canvas) → PNG 落盘 DataRecovery/Downloads）、`toggleFullscreen`（FLAG_FULLSCREEN + 隐藏工具栏）、`showPageSource`（evaluateJavascript 取 outerHTML → AlertDialog）、`toggleRotation`（横/竖屏切换）、`addCurrentBookmark`、`addCurrentQuickLink`、图片模式切换（复用 prefs.setImagesEnabled）。
  - 工具栏多模式：`applyToolbarMode()` 按 prefs.toolbarMode() 把 `browser_toolbar` 在根 LinearLayout 内移到末尾（底部模式）或顶部（其余模式）；`onResume` 重 apply（设置页改后即时生效）。
  - 搜索引擎：`loadUrlFromInput` 改用 `SearchEngines.prefix(prefs, prefs.searchEngine())` 拼接搜索 URL。
  - 九宫格 favicon：`buildQuickLinkItem` 在首字母回退层之上叠加 ImageView，`loadFavicon` 用 HttpURLConnection 异步拉取 `https://www.google.com/s2/favicons?domain=host&sz=64`，成功显示 favicon 隐藏首字母，失败保留首字母。
- `ui/activity/BrowserSettingsActivity.java`：新增搜索引擎 Spinner（YouTube/Google/Bing/Baidu/DuckDuckGo/自定义）+ 自定义前缀 EditText + 工具栏模式 RadioGroup（顶部/底部/三明治/双行）；`applyNightBackground` 夜间模式下根布局与文字切深色（设置页跟随夜间）。
- `ui/browser/BrowserPrefs.java`：新增 `searchEngine()/setSearchEngine`、`searchPrefix()/setSearchPrefix`、`toolbarMode()/setToolbarMode` 持久化。
- `ui/browser/SearchEngines.java`（新）：搜索引擎前缀解析，核心方法 `prefix(int engine, String customPrefix)` 无 Context 依赖（便于单测），`prefix(BrowserPrefs,int)` 便捷重载。
- `ui/browser/SearchEnginesTest.java`（新）：8 个用例覆盖 5 内置引擎 + 自定义（空/null 回退 Google）+ 完整 URL 拼接 + 未知索引回退 YouTube。

**资源**
- `res/menu/browser_menu.xml`：重写为 VIA 顺序全集（37 项 + 应用便捷/专属 4 项），每项带 24dp 单色线性 vector 图标。
- `res/values/strings_browser.xml`：新增 30+ 字符串（菜单全集标签、开发中 Toast、截图/查找/方向/全屏/源码/图片/书签/快捷链接反馈、搜索引擎名、工具栏模式名）。
- `res/layout/activity_browser.xml`：工具栏 LinearLayout 加 `@+id/browser_toolbar`（供代码重排）。
- `res/layout/activity_browser_settings.xml`：根加 `@+id/settings_root`；新增搜索引擎 Spinner、自定义前缀 EditText、工具栏模式 RadioGroup。
- `res/drawable/`：新增 `ic_rotation`、`ic_view_source`、`ic_print`、`ic_incognito`、`ic_scan`、`ic_save`、`ic_adblock`、`ic_screenshot`（24dp 单色 vector，fillColor=@color/text_primary）。

**未改**：proxy/*、build.gradle、AndroidManifest.xml（无新 Activity，全部复用既有声明）。

### 9.2 菜单与 VIA 顺序对齐

模拟器实测（PopupMenu 滚动逐屏 dump）项序：新建标签、关闭标签、打开方式、阅读模式、朗读、打印/PDF、字号、脚本、桌面/移动 UA、扫描二维码、刷新、标记为广告、网络日志、离线网页、图片：屏蔽、全屏、页面内查找、翻译、保存网页、设置、下载、分享、历史、书签、加入书签、加入收藏、添加快捷链接、无痕模式、夜间模式、游戏模式、方向、资源嗅探、查看源码、站点配置、工具、User-Agent、AI、广告拦截、自定义菜单、截图、代理、退出。与 VIA `k8.k` 工厂顺序一致（应用便捷/专属项单列于首尾，不破坏 VIA 中段顺序）。夜间/UA/图片/全屏/代理项标题动态反映当前状态。

落地（feasible）：打开方式、UA 切换、刷新、图片模式、全屏、页面内查找、翻译、设置、下载、分享、历史、书签、加入书签、添加快捷链接、夜间模式、方向、资源嗅探、查看源码、截图、代理、新建/关闭标签、退出。
占位（Toast「开发中」）：阅读模式、朗读、打印/PDF、字号、脚本、扫描二维码、标记为广告、网络日志、离线网页、保存网页、加入收藏、无痕模式、游戏模式、站点配置、工具、User-Agent、AI、广告拦截、自定义菜单。

### 9.3 模拟器验证结果（CleanDev_API35，真实运行）

1. **构建/安装**：`assembleDebug` ✅ BUILD SUCCESSFUL；`testDebugUnitTest` ✅ BUILD SUCCESSFUL（含新增 SearchEnginesTest 8 用例 + 既有代理/恢复/音乐单测全量）；`installDebug` ✅ Installed on CleanDev_API35；新增/改动文件 `ReadLints` 零 Error。
2. **菜单项数与顺序**：✅ 滚动逐屏 dump 确认 39 项，顺序与 VIA 一致（见 9.2）。
3. **底部工具栏**：✅ 设置页选「底部」→保存→`browser_toolbar` bounds 由顶部 `[0,88][900,144]` 变为底部 `[0,1440][900,1552]`，WebView 容器上移；force-stop 后 `am start --es url` 重启仍为底部（prefs 持久化 `toolbar_mode=1`）。
4. **搜索引擎可配置**：✅ 设置页 Spinner 展开见 6 引擎（YouTube/Google/Bing/Baidu/DuckDuckGo/自定义）；选 Google→保存→prefs 持久化 `search_engine=1`，重开设置 Spinner 回显「Google」；`SearchEnginesTest` 8 用例全绿证明前缀解析正确（Google→`https://www.google.com/search?q=`，自定义空/null→回退 Google，未知索引→回退 YouTube）。地址栏端到端键入搜索被模拟器 Gboard/LatinIME 劫持（见 9.5 阻碍 1），改由单测+持久化+Spinner 回显三路验证。
5. **九宫格 favicon**：✅ 首屏 6 个种子快捷链接全部加载到真实站点 favicon（百度/Bing/GitHub/YouTube/知乎/微博，截图 p1_home.png 确认彩色图标），非首字母占位。
6. **夜间模式**：✅ JS 开启+夜间开时 example.com 渲染为纯黑背景+白色标题+浅灰正文+蓝色链接（p_night_js.png）；设置页夜间跟随（深色根背景 #121212 系，p_settings_night.png）；夜间开时菜单项标题显示「夜间模式：关」（语义=当前开、点按关闭）。
7. **异常分支**：✅ 全程无崩溃（logcat 无 FATAL EXCEPTION）；占位菜单项 Toast 不崩溃；截图/查找/源码/方向/全屏切换均正常。

### 9.4 阻碍与切换记录（3 次失败规则）

1. **WebView.FindListener lambda 参数类型误用**：初版按 `(active, n, idx)` 三 int 写，实际签名 `(int activeMatchOrdinal, int numberOfMatches, boolean isDoneCounting)`，第 3 参为 boolean，导致 `findIndex=idx` 与 `idx+1` 编译失败（boolean↔int）。1 次失败即修正为正确形参名并按 activeMatchOrdinal 计数。未到 3 次。
2. **模拟器 Gboard/LatinIME 劫持地址栏输入**：`adb shell input text`/`keyevent` 注入到聚焦的地址栏 EditText 后，文本不进入字段（聚焦=true 但 text 不落），且 Gboard 的「搜索建议」会把输入导向系统默认浏览器（VIA）拉起 `mark.via/.Shell`，导致 my app 失焦。切换：① `adb shell ime set` 切 LatinIME 无效；② `pm disable-user mark.via` 临时禁用 VIA 阻断劫持（验证后 `pm enable` 恢复）；③ 地址栏端到端键入仍不可控，改由「SearchEnginesTest 单测 + prefs 持久化 + 设置 Spinner 回显」三路证明搜索引擎生效。判定：环境（IME）问题，非 app 缺陷，与 §8.6 同源。
3. **夜间模式在 example.com 不生效（JS 关闭时）**：测试中曾关 JS（`js_enabled=false`），导致 `applyDarkMode` 的 CSS 注入走 `evaluateJavascript` 不执行，仅 `WebSettingsCompat.setForceDark`（API 35 WebView 124 上对无 `prefers-color-scheme` 适配的极简页 example.com 视觉无效）。切换：重新开启 JS 后夜间 CSS 注入生效，example.com 渲染深色。**记为已知限制**：夜间模式 CSS 注入依赖 JS 开启；JS 关闭时仅 forceDark（对自适应站点生效，对极简静态页可能无效）。

### 9.5 已知限制与与 VIA 剩余偏差

**已知限制**
- 夜间模式 CSS 注入需 JS 开启；JS 关闭时仅 `WebSettingsCompat.setForceDark`，对无暗色适配的极简页可能不生效。
- 工具栏仅落地「顶部/底部」两种模式；「三明治/双行」为占位设置项（视觉同顶部），未做地址栏居中/双行布局。
- 搜索引擎端到端键入受模拟器 IME 劫持限制，未在模拟器直连验证出口 URL；由单测+持久化+Spinner 回显三路佐证。
- favicon 走 google s2/favicons（需联网），离线时回退首字母；未做本地缓存。
- 占位菜单项（朗读/脚本/广告拦截/AI/同步/阅读模式/打印/字号/扫描/离线/收藏/无痕/游戏/站点配置/工具/UA 预设/自定义菜单/保存网页）均为 Toast「开发中」，未实现子系统。

**与 VIA 剩余偏差**
- 菜单 19 项占位未落地（VIA 有真实子系统）；本项目菜单结构/顺序已对齐，功能子集待后续 Phase。
- 工具栏 4 模式仅落地 2 种（顶部/底部），三明治/双行待补。
- 主页九宫格图标用真实 favicon（已对齐 VIA），但无「图标颜色/风格」切换、无「清除图标缓存」。
- UA 仅桌面/移动两档切换，未做 VIA 的 10 档 UA 预设选择器（菜单 User-Agent 项占位）。
- 未实现 VIA 的「自定义菜单」拖拽排序（占位）。

### 9.6 构建 / 单测真实结果

- `.\gradlew.bat :app:assembleDebug`：✅ BUILD SUCCESSFUL。
- `.\gradlew.bat :app:testDebugUnitTest`：✅ BUILD SUCCESSFUL（含 SearchEnginesTest 8 用例 + 既有全量单测）。
- `.\gradlew.bat :app:installDebug`：✅ Installed on CleanDev_API35。
- 新增/改动文件 `ReadLints`：✅ 零 Error。

## 十、Phase 2：Mihomo 全协议内核与多订阅

### 10.1 内核方案

- 内置官方 `MetaCubeX/mihomo v1.19.29` Android 可执行内核，提供
  `arm64-v8a` 与 `x86_64` 两个 ABI；发布文件 SHA-256、GPLv3 许可证和
  对应源码地址记录在 `assets/mihomo/NOTICE.txt`。
- `MihomoProcess` 从 `applicationInfo.nativeLibraryDir/libmihomo.so` 启动独立进程，
  每次生成仅监听 `127.0.0.1` 的随机 mixed-port 和 controller-port。
- `MihomoConfigBuilder` 把用户选择节点放到 `Browser` select 组首位，并保留同订阅
  其它节点供切换；浏览器只连接本机 mixed-port。
- 旧纯 Java SS 引擎没有删除：Mihomo 内核不可执行且节点为兼容 SS 时才回退。

### 10.2 协议与订阅

- Clash YAML 的 `proxies:` 节点不再只取 SS，而是保留完整 YAML，交给 Mihomo 校验。
- Base64/明文 URI 支持 SS、VMess、VLESS（TLS/Reality、WS/gRPC）、Trojan、
  Hysteria2（端口跳跃/证书指纹）和 TUIC。
- `ProxyPrefs` 改为订阅配置档：每个订阅独立保存 URL、节点缓存、选中索引。
- 代理页顶部增加订阅下拉切换、新增和删除按钮；导入对话框支持一行一个 URL
  批量导入，`SubscriptionImportTest` 覆盖去重与非法行过滤。
- 节点列表改为 FlClash 风格卡片：节点名、端点摘要、协议徽标、单选状态。

### 10.3 真实订阅和出口验证

用户提供的真实订阅仅用于本轮运行测试，未写入源码、文档、样例或构建产物。

1. 订阅拉取成功，列表显示 VLESS 节点；整份配置被 Mihomo 接受。
2. 第一个实测节点两次返回 `429 Too Many Requests`。按规则定位为远端节点响应，
   切换第二个节点继续，不把它误报为客户端成功。
3. 第二个节点：Mihomo mixed-port 启动成功；经 `adb forward + SOCKS5` 请求
   `api.ipify.org` 返回合法公网 IP，真实出口链路通过。
4. WebView 初版使用 `socks://` ProxyController：回调报告成功，但 WebView 124
   请求未进入 Mihomo。切换 Mihomo mixed-port 的 `http://` 代理规则后，
   日志出现 `api.ipify.org:443 match Match using Browser[...]`，页面标题为
   `api.ipify.org`，证明浏览器流量真实经过所选节点。

### 10.4 本轮问题与切换记录

1. **本机没有 Go/gomobile/NDK**：无法按原计划现场编译 AAR。未虚报 AAR 完成；
   切换为官方同版本 Android 可执行内核，并在模拟器先验证 `-v` 和应用内执行权限。
2. **Mihomo 已监听但被误判启动超时**：`ProxyService` 主线程执行 Socket 就绪探测，
   Android 主线程网络限制使探测一直失败。迁移到单线程 worker 后，内核约 100ms
   完成启动并返回端口。
3. **WebView SOCKS 规则假成功**：见 10.3 第 4 项。一次真实失败后即切换内核原生
   HTTP mixed-port，无需等满三次；HTTPS 页面已端到端通过。
4. **真实节点 429**：远端节点问题，切换节点后通过。该节点未删除，用户仍可自行选择。

## 十一、Phase 2：VIA 高价值功能补全

- 原 19 个“开发中”菜单中已落地：阅读模式、系统 TTS 朗读、打印/PDF、字号、
  当前页脚本、标记广告域名、网络请求日志、离线 MHT/保存网页、收藏、无痕标签、
  游戏模式、站点工具、9 类 UA/自定义 UA、分享给 AI 应用、广告拦截。
- 广告拦截由 `BrowserAdBlocker` 在 `shouldInterceptRequest` 工作，不覆盖或绕开
  原有 `MediaSniffer/HiddenMediaSniffer` 链路。
- 工具栏四种模式全部有独立布局：顶部、底部、三明治（紧凑地址栏）和双行
  （导航行 + 独立地址行）。模拟器实测双行地址栏位于第二行；三明治隐藏
  后退/前进/主页而保留地址栏、标签和菜单。
- 尚未完成的两个菜单为“扫描二维码”和“自定义菜单拖拽”。二维码需要引入扫码
  解码器/相机流程；菜单拖拽需要把当前 XML PopupMenu 改为可排序持久化模型。
  二者继续明确显示“开发中”，没有伪装为已完成。

### 11.1 构建与检查

- `testDebugUnitTest`：通过（含代理 URI、多协议配置、批量订阅、广告域名测试）。
- `assembleDebug` / `installDebug`：通过；APK 位于
  `app/build/outputs/apk/debug/app-debug.apk`。
- `lintDebug`：仍失败，当前全仓库报告 330 errors / 411 warnings，首项位于既有
  `LyricsView`；本轮涉及文件经 IDE Lint 为零 Error。本轮发现并修复了
  `activity_browser.xml` 的 `android:tint`，改为 `app:tint`，没有创建 baseline
  或关闭检查来掩盖既有问题。

## 十二、最终双轴审查修复

最终按仓库规范与需求两条轴独立审查后，修复了以下可执行问题：

1. 无痕标签完成页面后不再写入浏览历史。
2. Clash YAML 仅把 `proxies:` 直属缩进的 `- ` 识别为新节点；ALPN 等嵌套列表
   会完整保留。新增 `preservesNestedYamlListsInsideNode` 回归测试。
3. Mihomo 启动失败会结束前台状态并停止 Service，不再留下常驻失败通知。
4. 关闭标签会停止加载、解除 client 并销毁被移除 WebView，避免反复开关标签泄漏。
5. 应用夜间 CSS 前先删除旧 `data-via-night` style；关闭夜间模式会真正恢复页面。
6. favicon HTTP 请求移出 Activity，下沉到 `util/FaviconFetcher` 网络边界。
7. 代理 Service 从有 6 小时后台限制的 `dataSync` 改为声明了具体用途的
   `specialUse` FGS；API 35 模拟器启动通过。
8. 订阅 URL（可能含 token）、节点密码及完整 Clash YAML 使用 Android Keystore
   AES-GCM 加密后再写 SharedPreferences；旧明文数据首次读取时自动迁移并删除。
   模拟器检查确认存在 2 个 `enc:v1` 密文值，旧 `sub_url`、订阅域名和
   `clashYaml` 明文均不再出现；迁移后真实节点出口仍通过。

## 十三、VIA 交互 1:1 对齐（基于模拟器实测 + via-runtime）

用户指出「只是 UI 相似」后，改为以模拟器中原版 `mark.via` 点击路径为准，
交叉对照 `.task/via-analysis.md` / `.task/via-runtime/`，再改本工程。

### 13.1 原版实测规格（CleanDev_API35）

**主页**
- 顶栏：网站信息 | 「主页」标题 | 扫描二维码（右上角）
- 中部：彩色 Logo + 胶囊搜索框（无顶栏地址输入）
- 底栏五键：后退 | 前进 | 主页 | 标签数 | 菜单

**溢出菜单（底部圆角面板，2×5，三页）**
1. 夜间模式 / 书签 / 历史 / 下载 / 隐身 / 分享 / 添加书签 / 电脑模式 / 工具箱 / 设置
2. 页内查找 / 保存 / 离线页面 / 翻译 / 源码 / 全屏 / 有图模式 / 资源嗅探 / 浏览器标识 / 网络日志
3. 扫描二维码 / 添加到桌面 / 朗读网页 / AI / 屏幕方向 / 广告拦截 / 标记广告 / 字体大小 / 举报网站 / 定制菜单
- 工具箱：留在菜单内翻到第 2 页（不关闭）
- 页脚：退出 | 收起；分页圆点可点

**定制菜单**
- 全屏页：返回 + 标题「定制菜单」+「重置」
- 上方显示池（5 列网格，长按拖拽排序；点击移到可用池）
- 下方可用池：「刷新网页 / 网站设定 / 脚本 / 清除数据 / 打印/PDF」等，点击添加

**二维码**
- 入口：主页顶栏右上角 + 菜单第 3 页
- 跳转：`CaptureActivity` 相机取景；结果回填地址栏并 `loadUrl`

### 13.2 本轮代码改动

- `activity_browser.xml`：主页改为 Logo + 胶囊搜索；默认底部五键
- `BrowserBottomMenu.java`：默认 30 项顺序对齐 VIA；工具箱翻页；定制双池网格
- `BrowserPrefs`：`menu_order_v2` 重置旧侧边菜单顺序；默认 toolbarMode=底部
- `BrowserActivity`：顶栏主页标题/扫码；扫码竖屏锁定；举报/清数据/桌面快捷方式
- `values` / `values-zh` 菜单文案与 VIA 中文一致（隐身/工具箱/定制菜单…）
- 依赖：`com.journeyapps:zxing-android-embedded:4.3.0`；`CAMERA` 权限
- `ViaAddressResolver` 已接入 `loadUrlFromInput`（TLD/https 偏好与搜索模板）
- `ViaSnifferStateMachine` 已接入 WebViewClient；角标显隐按 VIA commit+mediaLike+不支持站点策略

### 13.3 模拟器验收路径

1. 打开在线观影 → 见 Logo + 胶囊搜索 + 底栏五键 + 顶栏「主页」/扫码
2. 点菜单 → 首屏 10 项与 VIA 一致；点工具箱 → 第 2 页；点圆点 → 第 3 页
3. 定制菜单 → 拖拽排序 / 点击下架到可用池 / 重置
4. 顶栏扫码 → `CaptureActivity`；取消返回浏览器
5. 书签/历史/设置/下载：进入对应 Activity；电脑模式 Toast「已开启/已关闭」

### 13.4 仍未 1:1 的差距（诚实记录）

- 图标线条风格与 VIA 矢量资源尚未逐像素对齐（仍用本工程线性图标）
- 主页快捷链接仍显示在 Logo 下方；VIA 默认主页更空（快捷方式可配置）
- 工具栏长按/滑动自定义动作、站点信息详情页深度设置未完全复刻
- 「保存/翻译」在无网页时的禁用态（灰色）未做
- 地址栏编辑覆盖层（引擎|展开|搜索）与 Paste&Go 未复刻
- 嗅探列表仍由 `MediaSniffer` 填充；状态机暂只管角标策略与候选快照
- 设置部分子项（同步/密码管理器/皮肤/手势绑定等）仍为「开发中」占位
- 本轮已重新执行全仓库 `:app:lintDebug` 并通过；未用 baseline 绕过。

### 13.5 设置页对齐（模拟器实测 VIA 7.2.1）

- 首页分类：**通用 / 定制 / 隐私 / 高级 / 脚本 / 关于**（与 VIA 一致）
- 通用：浏览器标识、清除数据、广告拦截、夜间模式、工具栏、定制菜单、搜索引擎、首页、字号等
- 高级首项保留本工程 **代理设置 → ProxyActivity**（代码未删；菜单默认第 4 页亦可见「代理」）
- 验收：`installDebug` 后菜单→设置→高级→代理设置 可进入 Mihomo 订阅页


### 13.6 本轮回归：在线观影入口、外部链接、HTTP 与代理链路

**代码修复**
- `BrowserActivity` 新增 VIA 风格启动解析：`url` extra、`ACTION_VIEW` data、`ACTION_SEND` 文本、`WEB_SEARCH`、`PROCESS_TEXT` 都统一进入 `ViaAddressResolver`。
- `BrowserActivity.onNewIntent()` 处理 `singleTask` 已运行实例，外部链接不会只唤醒 Activity 而丢失目标 URL。
- `AndroidManifest.xml` 为在线观影浏览器补齐 `VIEW/SEND/WEB_SEARCH/PROCESS_TEXT` intent-filter。
- `network_security_config.xml` 增加浏览器所需的全局 HTTP 明文访问；酷狗敏感 HTTPS API 仍用专门 domain-config 禁止明文。
- `activity_browser.xml` 底栏菜单按钮可访问文案从“设置”改为“菜单”，避免与 VIA 底栏五键语义不一致。

**模拟器实测路径**
1. `MainActivity` → 底部“关于” → `AboutActivity`：dump 看到 `about_online_movie_card`、标题“在线观影”、副标题“使用内置浏览器在线观看视频”、按钮“打开”。
2. 点“在线观影/打开” → 前台为 `BrowserActivity`：顶部为“网站信息 | 主页 | 扫描二维码”，底部为“后退 / 前进 / 首页 / 标签 / 菜单”。
3. 点底部“菜单” → 底部圆角 2×5 菜单首屏出现“夜间模式、书签、历史、下载、隐身、分享、添加书签、电脑模式、工具箱、设置”，分页为 4 页（第 4 页包含本工程扩展代理）。
4. 菜单 → 设置 → 高级 → 代理设置 → `ProxyActivity`：可见订阅输入、拉取订阅、载入样例订阅、节点列表、启动代理。
5. 代理页点击“载入样例订阅”→“拉取订阅”：列表出现 `SampleLocal` 与 `SampleRemote`，节点可单选。
6. 点“启动代理”：状态显示“代理运行中 · Mihomo，本地 SOCKS5/HTTP：127.0.0.1:35851”，按钮变为“停止代理”。
7. 停止代理后发送 `--es url example.com` 到 `BrowserActivity`：地址栏为 `http://example.com/`，页面渲染 `Example Domain`；确认 HTTP 明文不再触发 `ERR_CLEARTEXT_NOT_PERMITTED`。

**验证命令**
- `.\\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`：通过。
- `.\\gradlew.bat :app:lintDebug`：通过；本轮补齐了新增/既有 browser 文案的 zh 翻译缺口。
- `adb install -r -d app\build\outputs\apk\debug\app-debug.apk`：Success。
- 关键 dump 留存：`.task/verify-about-after-install.xml`、`.task/verify-browser-after-patch.xml`、`.task/verify-browser-menu-after-patch.xml`、`.task/verify-settings-advanced-after-patch.xml`、`.task/verify-proxy-fetch-after-patch.xml`、`.task/verify-proxy-start-after-patch.xml`、`.task/verify-browser-http-after-patch.xml`。

**阻碍记录**
- 本轮没有功能/函数连续 3 次实现失败。
- ADB shell 对包含完整 `https://example.com` 的复杂命令曾被本地执行策略截断；已改用 `--es url example.com` 验证同一解析与加载链路，并额外用 HTTP 页面渲染覆盖明文网络回归。



### 13.7 本轮补充：VIA 资源布局优先逆向与按钮尺寸回归

**用户要求修正**：不能只凭截图猜 UI；应优先逆向 VIA 原 APK 的布局文件，再用截图/dump 校准。代理模块是本项目扩展功能，必须保留。

**VIA 逆向证据**：
- APK：`.task/via.apk`。
- apktool 解包资源：`.task/via-decompiled/res/res`。
- 主容器/底栏：`.task/via-decompiled/res/res/layout/q.xml`：底部 `RadioGroup @id/d_`，5 个等权按钮，`minHeight=48dp`。
- 顶部搜索/标题：`.task/via-decompiled/res/res/layout/j.xml`：标题 `@id/fh` 高 48dp，右侧扫描 `@id/cf` 宽高 `@dimen/g=48dp`，padding `@dimen/e=13dp`。
- 尺寸常量：`.task/via-decompiled/res/res/values/dimens.xml`：`b=48dp`、`g=48dp`、`e=13dp`。
- 运行时校准：`.task/via-ui.xml` 中顶栏 `[0,128][900,224]`、底栏 `[0,1456][900,1552]`；底栏五键各 180px 宽。
- 结构化记录已写入：`.task/via-ui-interaction-spec.md`。

**本轮代码修复**：
- `app/src/main/res/layout/activity_browser.xml`
  - 去掉顶栏/地址行 4dp 横向 padding，让「网站信息」与「扫描二维码」从屏幕边缘 48dp 对齐 VIA。
  - 底栏按钮背景从圆角卡片改为 VIA 风格透明 ripple。
  - 标签按钮保持完整 1/5 底栏点击区。
  - 前进按钮改用独立 `ic_forward`，不再旋转 `ic_arrow_up`，避免 Android accessibility bounds 被旋转压缩成 48dp。
- `app/src/main/res/drawable/bg_via_toolbar_button.xml`：新增透明 ripple。
- `app/src/main/res/drawable/ic_forward.xml`：新增前进图标。
- `app/src/main/res/drawable/ic_scan.xml`：改为 VIA 风格四角扫描框。
- `app/src/main/res/drawable/ic_tabs.xml`、`bg_tab_badge.xml`：改为 VIA 风格标签数字方框。
- `app/src/main/java/com/example/cleanrecovery/ui/activity/BrowserActivity.java`
  - 后退/前进 contentDescription 对齐 VIA：`网页后退`、`网页前进`。
  - 无历史时按钮仍保持完整点击区，只降透明度；点击 no-op。
  - 标签容器也绑定点击/长按，点击区与显示区一致。
- `app/src/main/res/values/strings_browser.xml`、`values-zh/strings_lint_missing.xml`：新增 VIA 后退/前进文案。

**真实遇到的问题与处理**：
1. 顶栏左右各偏 4dp：由本项目 `browser_toolbar_primary` padding 造成。直接删除 padding 后，dump 对齐 VIA `[0..96] / [96..804] / [804..900]`。
2. 前进按钮 bounds 仍为 `[222,1456][318,1552]`：不是权重失效，而是 `rotation=90` 导致无障碍 bounds 压缩。改为独立 `ic_forward.xml` 后变为 `[180,1456][360,1552]`。
3. 安装后系统通知/媒体权限弹窗拦截了点击路径：先按系统弹窗真实按钮处理，再重新执行关于入口、菜单、代理路径；未把失败点击当作完成。
4. 一次关于页坐标误点到「全网下载」：读取 `.task/verify-viafix5-about.xml` 中 `about_online_movie_entry [700,1113][812,1185]` 后重跑，确认进入 BrowserActivity。

**验证结果**：
- 构建/测试/lint：`./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 通过。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，`adb install -r -d` 成功。
- 关于入口：`.task/verify-viafix5-about.xml` 显示 `about_online_movie_card`、标题「在线观影」、按钮「打开」；点击后 `.task/verify-viafix5-about-to-browser.xml` 进入浏览器。
- 顶栏/底栏像素：`.task/verify-viafix4-browser-home.xml` 和 `.task/verify-viafix5-about-to-browser.xml`：
  - 网站信息 `[0,128][96,224]`
  - 主页标题 `[96,128][804,224]`
  - 扫描二维码 `[804,128][900,224]`
  - 网页后退 `[0,1456][180,1552]`
  - 网页前进 `[180,1456][360,1552]`
  - 主页 `[360,1456][540,1552]`
  - 标签 `[540,1456][720,1552]`
  - 菜单 `[720,1456][900,1552]`
- 菜单第一页：`.task/verify-viafix4-menu-p1.xml`：`夜间模式/书签/历史/下载/隐身`、`分享/添加书签/电脑模式/工具箱/设置`。
- 工具箱交互：`.task/verify-viafix4-toolbox.xml`：点击「工具箱」未关闭弹层，翻到工具页，显示 `页内查找/保存/离线页面/翻译/源码` 等。
- 代理保留：`.task/verify-viafix5-settings-advanced.xml` 高级页首项为「代理设置」；`.task/verify-viafix5-proxy.xml` 打开 `Mihomo 代理`，显示订阅、节点、启动代理按钮。

### 13.8 本轮补充：底部菜单 UI 与下载菜单功能纠偏

**用户反馈**：底部右下角菜单打开后的 2×5 子功能按钮仍未完整对齐 VIA；按钮图标、大小、颜色、字体、粗细、视觉、交互、内外边距，以及每个功能点击后的界面和后端逻辑都需要继续实现。代理属于本项目扩展，保留但不按 VIA 删除。

**本轮逆向/校准依据**：
- VIA 菜单 dump：`.task/via-grid-final.xml`：菜单 cell bounds 为 `[16,1080][189,1240]` 等，2 行 × 5 列，每格约 174×160px。
- 本轮修复前项目 dump：`.task/verify-viafix4-menu-p1.xml`：cell 起点 `[16,1052]`，弹层过高。
- 修复后 dump：`.task/verify-menu-ui-pass1.xml`：cell 起点 `[16,1080]`，与 VIA 对齐。
- 工具箱修复后 dump：`.task/verify-toolbox-ui-pass1.xml`：工具箱页显示固定文案 `全屏 / 有图模式`，不再显示动态状态 `已进入全屏 / 图片：显示`。

**本轮代码修复**：
- `BrowserBottomMenu.java`
  - 菜单根布局底部贴边，grid 高度改为 160dp，cell 高度 80dp。
  - 图标尺寸调整为 26dp，文字 12sp、normal、去 includeFontPadding。
  - cell/底部按钮 ripple 改为透明 VIA 风格。
  - footer 电源/收起按钮改用自绘图标，避免 Android 内置图标视觉偏差。
- 新增资源：
  - `bg_via_menu_cell.xml`
  - `ic_via_power.xml`
  - `ic_via_collapse.xml`
- `BrowserActivity.java`
  - 工具箱中的 `全屏`、`有图模式` 文案固定为 VIA 文案，状态只通过 Toast/行为反馈。
  - 菜单 `下载` 从 `UniversalDownloadActivity` 改为 `BrowserDownloadsActivity`，避免把 VIA 下载管理误接到本项目“全网下载”。
- 新增 `BrowserDownloadsActivity.java`
  - 读取 `DownloadTaskDbHelper.getAllTasks()`，显示下载队列/历史。
  - 点击已完成任务尝试打开文件；长按复制下载链接；右上角清理已完成/失败/取消记录。
- `DownloadTaskDbHelper.java`
  - 新增 `getAllTasks()`，提供下载管理页后端数据。
- `activity_browser_list.xml`
  - 通用二级列表页工具栏改为 48dp、透明按钮、白底，更接近 VIA。
- `BookmarksActivity.java`、`HistoryActivity.java`、`TabsActivity.java`
  - 替换部分系统内置按钮图标和 contentDescription。

**已验证路径**：
- 构建/单测/lint：`./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 通过。
- 安装：`adb install -r -d app/build/outputs/apk/debug/app-debug.apk` 成功。
- 菜单视觉：`.task/verify-menu-ui-pass1.xml` + `.task/verify-menu-ui-pass1.png`。
- 工具箱视觉：`.task/verify-toolbox-ui-pass1.xml` + `.task/verify-toolbox-ui-pass1.png`。
- 书签入口：`.task/debug-click-bookmark-now.xml`，进入标题「书签」页，右上「将当前页加入书签」。
- 历史入口：`.task/verify-click-history2.xml`，进入标题「历史」页，显示历史记录，右上「清空历史」。
- 隐身入口：`.task/verify-click-incognito2.xml`，点击后标签数从 1 变 2，说明新建隐身标签路径生效。
- 设置入口：`.task/verify-click-settings2.xml`，进入设置首页。
- 下载入口：`.task/verify-click-browser-downloads.xml`，进入标题「下载」页，右上「清除已完成任务」，空状态「没有下载任务」。

**仍需继续逐项验收/补齐**：
- 分享：需要验证空页/有 URL 两种路径，确保 chooser 行为和 VIA 接近。
- 添加书签：需要验证空页提示、有 URL 添加、重复添加、书签页可见。
- 电脑模式：需要验证 UA 切换、当前 WebView reload/不 reload 行为是否与 VIA 一致。
- 工具箱二级功能：页内查找、保存、离线页面、翻译、源码、全屏、有图模式、资源嗅探、浏览器标识、网络日志都需要逐项点击 dump 和后端验证。
- 第 3/4 页菜单：扫描、添加到桌面、朗读、AI、方向、广告拦截、标记广告、字体、网站设定、定制菜单、代理等仍要按项回归；代理保留但不删除。

## 2026-07-25 VIA 功能逻辑逆向与网站设定补齐

- 逆向依据：`.task/via-decompiled/res/smali/k8/k.smali` 菜单资源组装、`.task/via-decompiled/res/smali/e8/v6.smali` / `.task/via-runtime/java/e8_v6_fallback.java` 功能分派、`public.xml` 字符串映射。
- 新增实现：按网站 host 保存浏览器标识、字体大小、广告拦截例外、Cookies 清理/禁用状态；顶部「网站信息」入口现在展示 VIA 风格的网站设定列表。
- 已保留：代理入口、`proxy/` 包、`ProxyActivity`、Mihomo 资产与底部菜单代理项均未删除。
- 验证：`assembleDebug`、`testDebugUnitTest`、`lintDebug` 均成功；模拟器点击 `网站信息 → 浏览器标识 → Windows (Chrome)` 后页面 reload 正常，dump 保存在 `.task/verify-site-*.xml`。
- 卡壳点：`adb shell am start` 命令被本地执行策略拦截，改用 `adb shell cmd activity start-activity` 启动 Activity。

## 2026-07-25 继续：保存/离线页面/分享

- 逆向依据：`e8_v6_fallback.java` 中 `m0(String)` 保存弹窗、`l8(String,String)` 书签/离线处理、资源字符串 `分享链接/系统分享/网页已保存至：书签 > 离线页面`。
- 新增离线页后端：SQLite `offline_pages` 表 + 本地 HTML 快照文件 + 离线列表 Activity。
- 用户路径：在线观影浏览器 → 右下角菜单 → 工具箱 → 保存；再进入 工具箱 → 离线页面 → 点击条目，能加载本地 file 页面。
- 分享路径：右下角菜单 → 分享 → 弹出 VIA 风格分享选项，再复制链接或进入系统分享。
- 回归问题：MHT 打开报 `ERR_ACCESS_DENIED`，补 WebView 文件访问后转为 HTML 快照；HTML 初版因 `\u003C` 未解码空白，修复 unicode escape 后通过截图验证。
- 验证产物：`.task/verify-share-dialog-via.xml`、`.task/verify-offline-list-html.xml`、`.task/verify-offline-open-html-fixed.png`。

## 2026-07-25 广告/嗅探后端开发经验

- 先逆向 VIA 的 `D0/T1`，再实现：CSS 注入、规则响应、资源候选表三条链路要分开做；只做菜单点击或 Toast 会导致后端空转。
- 「标记广告」正确抽象是 host + CSS selector 规则，不是把当前站点整个加入黑名单；否则会把用户正在看的站点也拦掉。
- WebView 的资源嗅探不能只依赖 `<video src>`，测试时最好用 `fetch(...mp4...)` 或带 `Range` 的请求触发 `shouldInterceptRequest`，这样回归稳定。
- VIA 的资源按钮出现条件是“当前 WebView 已 commit + 存在 media-like 候选 + 非不支持站点”；本项目同步状态机候选到 UI 列表后，按钮数量和下载列表一致。
- 代理模块是项目扩展，广告/嗅探代码只改浏览器与设置，不触碰 `proxy/` 包和 Manifest 注册项。
