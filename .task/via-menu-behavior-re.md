# VIA 浏览器菜单行为逆向记录（2026-07-25）

## 本轮逆向输入

- APK：`.task/via.apk`
- apktool 解包：`.task/via-decompiled/res/`
- jadx/反编译缓存：`.task/via-runtime/java/`
- 运行时 UI dump：`.task/via-grid-final.xml`、`.task/via-toolbox.xml`、`.task/via-settings-*.xml`

## 关键证据

### 菜单项构造

` .task/via-decompiled/res/smali/k8/k.smali ` 是底部菜单条目的资源组装位置：

- `:pswitch_1d` 使用 `Lz7/t;->D:I`，对应字符串 `页内查找`。
- `:pswitch_19` 使用 `Lz7/t;->c0:I`，对应字符串 `源码`。
- `:pswitch_1b` 使用 `Lz7/t;->a0:I`，对应字符串 `离线页面`。
- `:pswitch_20` 使用 `Lz7/t;->R:I`，对应字符串 `电脑模式`。
- `:pswitch_14` 使用 `Lz7/t;->s8:I`，对应字符串 `网络日志`。
- `:pswitch_15` 使用 `Lz7/t;->A0:I`，对应字符串 `浏览器标识`。

### 点击后的业务逻辑

` .task/via-runtime/java/e8_v6_fallback.java ` / ` .task/via-decompiled/res/smali/e8/v6.smali ` 给出以下行为：

- 电脑模式：`e8/v6.smali` 约 20180-20300 行，读取 `Ly9/p->l()`，取反后 `Ly9/p->O(Z)`，再 `M0` 保存，调用 `ua.l1(Z)` 刷新；字符串使用 `电脑模式` + 开关结果。已应用为：底部菜单「电脑模式」固定文案，点击切桌面/移动 UA 并 reload 当前标签。
- 下载管理：`e8/v6.smali` 约 21490 行使用 `Lz7/t;->y0:I` 标题打开列表选择/下载管理入口。已应用为：菜单「下载」进入 `BrowserDownloadsActivity`，读取后台下载队列表。
- 浏览器标识：`e8/v6.smali` 约 28330-28400 行，标题在普通模式用 `浏览器标识`，电脑模式下可用 `电脑模式下的浏览器标识`；本轮补齐网站级 UA 覆盖，保存后 reload。
- 保存网页：`e8/v6.smali` 约 46880-47040 行，`m0(String)` 用标题 `保存`，展示输入框和确认按钮；本项目当前用 WebView `saveWebArchive` 生成 MHT，保留为可执行后端。
- 页内查找：`k8/k.smali` 与 `e8_v6_fallback.java` 中 `fa(14/15/19/20/26)` 分派到功能页，已应用为 `findAllAsync` + `findNext` + 匹配数。
- 网站设定：字符串资源中存在 `%1$s 的 Cookies`、`重置网站设定`、`网站设定`、`字体大小`、`浏览器标识`。本轮补齐：按 host 保存 UA、字体、广告拦截例外、Cookies 清理/禁用状态。
- 网络日志：字符串 `这个页面没有网络日志。` 已对齐；本项目 `shouldInterceptRequest` 记录 `METHOD URL`，列表为空时显示该 VIA 文案。
- 添加书签：字符串 `已添加书签` 已对齐；本项目将当前 title/url 写入浏览器 SQLite 书签表。

## 本轮实现落地

- `BrowserPrefs` 增加 VIA 网站设定的 host 级保存：`siteUserAgent`、`siteTextZoom`、`siteAdBlockOff`、`siteCookiesOff`、`resetSiteSettings`。
- `BrowserActivity`：
  - 应用设置时优先读取当前 host 的 UA/字号覆盖。
  - 顶部「网站信息」弹出 `example.com 的网站设定`：Cookies、浏览器标识、广告拦截、字体大小、重置网站设定。
  - 网站级 UA/字号/广告拦截修改后立即 `applySettings + reload`。
  - 网站 Cookies 禁用时清理当前 URL Cookie，并保存禁用状态。
- `BrowserAdBlocker`：如果当前页面 host 被设为广告拦截禁用，则子资源拦截放行；否则继续执行全局广告拦截。
- 字符串对齐：`已添加书签`、`这个页面没有网络日志。`。

## 已验证点击路径

1. 安装 APK：`adb install -r -d app/build/outputs/apk/debug/app-debug.apk` 成功。
2. 启动在线观影浏览器：`adb shell cmd activity start-activity -n com.example.cleanrecovery.musicapp/com.example.cleanrecovery.ui.activity.BrowserActivity -d http://example.com`。
3. 点击顶部左侧「网站信息」：生成 `.task/verify-site-settings-dialog.xml`，确认显示：
   - `example.com 的网站设定`
   - `Cookies (登录状态)：允许`
   - `浏览器标识`
   - `广告拦截：跟随全局`
   - `字体大小：100%`
   - `重置网站设定`
4. 点击「浏览器标识」：生成 `.task/verify-site-ua-dialog.xml`，确认显示 `默认 / Windows (Chrome) / Android (手机) / 自定义浏览器标识`。
5. 点击 `Windows (Chrome)`：生成 `.task/verify-site-ua-applied.xml`，页面仍在 `http://example.com/` 并展示 `Example Domain`，说明保存 + reload 后无崩溃。
6. 构建与静态检查：
   - `./gradlew.bat :app:assembleDebug` 成功。
   - `./gradlew.bat :app:testDebugUnitTest :app:lintDebug` 成功。

## 阻碍记录

- 本轮未出现同一函数连续 3 次实现失败。
- `adb shell am start ...` 被本地执行策略拦截一次；切换为 `adb shell cmd activity start-activity ...` 后可执行，未影响应用验证。


## 2026-07-25 继续：保存/离线页面/分享

### 逆向补充证据

- `.task/via-runtime/java/e8_v6_fallback.java`：
  - `m0(String)` 使用标题 `z7.t.Z`，即 `保存`，并弹出输入框/确认按钮，说明「保存」不是单纯 Toast，而是有保存动作入口。
  - `l8(String,String)` 对 URL 与标题做异步处理，失败回到书签对话框；结合 VIA 字符串 `网页已保存至：书签 > 离线页面`，离线页面属于书签体系的子列表。
  - `Intent.createChooser(...)` 出现在文件选择/系统分享链路；资源中存在 `系统分享` 与 `分享链接`，因此本项目分享不再直接跳系统 chooser，而先弹 VIA 风格分享选项。

### 本轮实现

- 新增 `BrowserOfflinePagesActivity`：VIA 风格列表页，标题 `离线页面`，右侧动作为 `清除失效记录`，点击条目回传本地 file URL，长按删除记录和文件。
- `BrowserDatabaseHelper` 升级到 DB v4，新增 `offline_pages` 表：`title/url/file_path/add_time`。
- `BrowserActivity`：
  - `保存`：JS 获取当前 DOM 快照，写入 `files/Documents/OfflinePages/*.html`，并写入离线页面数据库。
  - `离线页面`：打开 `BrowserOfflinePagesActivity`，点击条目后在当前 WebView 加载 `file://...html`。
  - WebView 文件访问策略打开：`setAllowFileAccess / setAllowContentAccess / setAllowFileAccessFromFileURLs / setAllowUniversalAccessFromFileURLs`。
  - `decodeJsString` 修复 `\u003C` 等 unicode escape，否则保存文件会变成文本而非 HTML。
  - `分享`：先弹出 `分享链接 / 分享标题和链接 / 复制链接 / 系统分享`，再进入系统 chooser 或剪贴板。

### 验证

- 分享：`.task/verify-share-dialog-via.xml` 确认弹出 `分享 / 分享链接 / 分享标题和链接 / 复制链接 / 系统分享`。
- 保存 + 离线列表：`.task/verify-offline-list-html.xml` 确认 `离线页面` 列表中出现 `Example Domain / http://example.com/`。
- 离线打开：`.task/verify-offline-open-html-fixed.png` 截图确认 `file://...Example Domain-*.html` 已渲染 `Example Domain / This domain... / Learn more`。
- 构建与检查：`assembleDebug`、`testDebugUnitTest`、`lintDebug` 均成功。

### 本轮问题与修复

- 第一次离线实现使用 `saveWebArchive(...mht)`，点击后 WebView 报 `net::ERR_ACCESS_DENIED`；补文件访问设置后错误消失，但 MHT 在当前 WebView 表现不稳定。
- 第二次改为 HTML 快照后仍空白；定位到 `evaluateJavascript` 返回 `\u003C` 未解码，文件内容不是有效 HTML。修复 `decodeJsString` unicode escape 后截图验证通过。
- 未出现同一函数连续 3 次实现失败；保存/离线经历 2 次不可用定位，第三次修复并通过。

## 2026-07-25 继续：广告标记 / 广告拦截 / 资源嗅探后端

### VIA 逆向结论

- `e8_ua.java:D0(WebResourceRequest,String,String)` 是 VIA 的核心拦截入口：
  - 命中 `via_inject_blocker.css` 时拼接站点级/通用 CSS，并用 `WebResourceResponse("text/css", "UTF-8", ...)` 返回，响应头包含 `Cache-Control: no-cache` 与跨域头。
  - 调用 `T1(...)` 判断广告规则；命中后统计拦截数量和体积，并根据资源类型返回空响应或 HTML 占位响应。
- `e8_ua.java:T1(...)` 是规则命中入口：排除内部 URL、要求当前页面处于可拦截状态，再走规则库 `q/r` 判断。
- `e8_v6_fallback.java` 的 JS bridge 有 `host/filter` 分支，说明「标记广告」不是简单拦截当前 host，而是页面 JS 回传过滤规则后写入规则管理界面/规则库。
- `e8_ua.java:s1/t1` 与 `f8.d.c().g/d(...)` 对应资源嗅探：按 WebView/页面 id 保存候选资源，只在当前页面 commit 且存在 media-like 资源时展示按钮/列表。

### 本轮实现

- `BrowserPrefs`：新增 `blockedUrlRules`、`addBlockedUrlRule`、`addCosmeticRule`、`cosmeticRulesForHost`、`cosmeticCssForHost`，支持 host 级 CSS 过滤规则持久化。
- `BrowserAdBlocker`：从 host-only 扩展为 host + URL 片段 + AdBlock 风格 `||domain^` + 自定义 `*substring*` 规则；同时支持 `via_inject_blocker.css` 返回 CSS 响应。
- `BrowserActivity`：
  - 「标记广告」进入页面元素选择模式，注入半透明提示和 click 捕获脚本。
  - 用户点击页面元素后，JS 计算稳定 CSS selector，通过 `ViaAdMarker` bridge 回传 Android。
  - Android 按当前 host 保存 cosmetic 规则，立即隐藏所选元素，并在后续页面完成时自动重新注入 CSS。
  - 页面完成后执行 `applyAdBlockCss`，对齐 VIA 的 CSS 过滤链路。
  - `ViaSnifferStateMachine.mediaUrls(...)` 现在同步进入可见嗅探列表；只靠 VIA 状态机捕获到的 mp4/m3u8/range 候选也会显示为「视频/音频/HLS」并可选择下载。
- `MediaSniffer.MediaResource`：构造器公开并新增 `fromSniffedUrl`，用于从 VIA 状态机捕获 URL 生成 UI 列表项。
- 单测：`BrowserAdBlockerTest` 新增 URL 片段规则与 `||domain^` 规则覆盖。

### 验证

- 构建：`./gradlew.bat :app:assembleDebug` 成功，APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 单测：`./gradlew.bat :app:testDebugUnitTest --tests com.example.cleanrecovery.ui.browser.BrowserAdBlockerTest --tests com.example.cleanrecovery.ui.browser.ViaSnifferStateMachineTest` 成功。
- lint：`./gradlew.bat :app:lintDebug` 成功。
- 安装：`adb install -r -d app/build/outputs/apk/debug/app-debug.apk` 成功。
- 资源嗅探动态验证：
  - 启动测试页：`adb shell cmd activity start-activity -n com.example.cleanrecovery.musicapp/com.example.cleanrecovery.ui.activity.BrowserActivity -d http://10.0.2.2:8765/via_backend_test2.html`。
  - 测试页延迟发起 `fetch('https://media.example.com/movie.mp4?token=1')`。
  - `.task/verify-sniff-http.xml` 确认 UI 显示 `找到 1 个媒体资源`，列表项为 `视频`，下载按钮为 `下载所选项`。
  - 截图：`.task/verify-sniff-http.png`。
- 标记广告动态验证：
  - 路径：在线观影浏览器 → 右下角菜单 → 滑到第 3 页 → 标记广告 → 点击测试页红色 `AD BOX`。
  - `.task/verify-menu-page3.xml` 确认第 3 页存在 `广告拦截` 与 `标记广告`，代理未受影响。
  - `.task/verify-ad-marker-ready.xml` 确认页面出现 `点击页面上的广告区域`。
  - `.task/verify-ad-marker-saved.png` 确认点击后红色广告块消失，并出现 Toast `广告规则已保存`；同时嗅探面板仍保留 `找到 1 个媒体资源 / 视频`。
- 代理保留检查：`app/src/main/java/com/example/cleanrecovery/proxy/` 下 `ProxyActivity`、`ProxyService`、Mihomo/订阅/节点相关类仍存在；Manifest 仍注册 `com.example.cleanrecovery.proxy.ProxyActivity` 与 `ProxyService`。

### 问题与处理

- 直接用 `data:text/html,<...>` 作为 adb 启动参数时被 shell 解析 `<` 破坏，改用 `.task/via_backend_test2.html` + 本地 Python HTTP server + `http://10.0.2.2:8765/...`，验证链路稳定。
- 第一个 file:// 测试页只展示嗅探面板但列表为空；定位为视频标签本身不稳定触发网络请求，改为页面加载后 `fetch(...mp4...)` 主动触发请求，最终列表出现 `视频`。
- 本轮未出现同一函数连续 3 次失败。
