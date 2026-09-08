package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import java.util.List;
import java.util.Locale;

/** HTML used by both the customization preview and the HTML/CSS homepage. */
public final class HomePageRenderer {
    private final Context context;
    private final BrowserPrefs prefs;
    private final BrowserDatabaseHelper dbHelper;
    public HomePageRenderer(Context context) {
        this.context = context;
        prefs = new BrowserPrefs(context);
        dbHelper = BrowserDatabaseHelper.getInstance(context);
    }
    public String render(String customCss) {
        StringBuilder boxes = new StringBuilder();
        List<BrowserDatabaseHelper.Entry> links = prefs.homeMode() == 2
                ? dbHelper.listBookmarks() : dbHelper.listQuickLinks();
        for (BrowserDatabaseHelper.Entry entry : links) {
            String title = html(entry.title);
            String url = html(entry.url);
            String first = TextUtils.isEmpty(entry.title) ? "?" : html(entry.title.substring(0, entry.title.offsetByCodePoints(0, 1)));
            String icon = first;
            if (prefs.homeFavoriteIconStyle() == 0) {
                String host = Uri.parse(entry.url).getHost();
                if (host != null) icon += "<img class=\"favicon\" src=\"https://www.google.com/s2/favicons?sz=64&amp;domain="
                    + Uri.encode(host) + "\" onerror=\"this.style.display='none'\">";
            }
            boxes.append("<div class=\"box\"><p class=\"title\" style=\"background:").append(prefs.homeFavoriteIconColor() == 1 ? "transparent" : cssColor(siteColor(entry.url))).append("\">").append(icon)
                    .append("</p><div class=\"overlay\"></div><p class=\"url\">").append(title)
                    .append("</p><a href=\"").append(url).append("\" title=\"").append(title)
                    .append("\"></a></div>");
        }
        int bg = prefs.homeBackgroundColor();
        int searchAlpha = Math.round(255 * prefs.homeSearchAlpha() / 100f);
        int strokeAlpha = Math.round(255 * prefs.homeSearchStrokeAlpha() / 100f);
        int foreground = prefs.homeBackgroundDark() ? 0x00ffffff : 0x00000000;
        int logoHeight = prefs.homeLogoSize() == 0 && prefs.homeLogoWidth() == 0 ? 72 : prefs.homeLogoSize();
        int logoWidth = prefs.homeLogoWidth();
        int favoriteWidth = prefs.homeFavoriteWidth();
        int favoriteHeight = prefs.homeFavoriteHeight();
        String backgroundImage = dataUri(prefs.homeBackgroundUri());
        String logo;
        if (prefs.homeLogoMode() == 4) {
            logo = "<br>";
        } else if (prefs.homeLogoMode() == 1) {
            String customLogo = dataUri(prefs.homeLogoUri());
            logo = customLogo.isEmpty() ? defaultLogo()
                    : "<img class=\"smaller\" src=\"" + html(customLogo) + "\">";
        } else if (prefs.homeLogoMode() == 2 || prefs.homeLogoMode() == 3) {
            logo = "<span class=\"logo_text\">" + (prefs.homeLogoMode() == 3 ? prefs.homeLogoText() : html(prefs.homeLogoText())) + "</span>";
        } else {
            logo = defaultLogo();
        }
        StringBuilder appearance = new StringBuilder();
        appearance.append("body{background-color:").append(cssColor(bg)).append("}");
        if (!backgroundImage.isEmpty()) {
            appearance.append("body:before{content:'';position:fixed;inset:-12px;background:linear-gradient(rgba(0,0,0,")
                    .append(prefs.homeBackgroundShade() / 100f).append("),rgba(0,0,0,").append(prefs.homeBackgroundShade() / 100f)
                    .append(")),url('")
                    .append(backgroundImage).append("') center/cover no-repeat;opacity:")
                    .append(prefs.homeBackgroundOpacity() / 100f).append(';');
            if (prefs.homeBackgroundBlur()) appearance.append("filter:blur(10px);");
            appearance.append("z-index:-1}");
        }
        appearance.append(".favicon{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;padding:0;filter:none}.box .title{position:relative;overflow:hidden}");
        appearance.append(".logo{width:auto;height:auto;overflow:visible;border-radius:0;white-space:normal;overflow-wrap:break-word;font-size:")
            .append(prefs.homeLogoTextSize()).append("px;font-weight:").append(prefs.homeLogoBold() ? "700" : "400")
            .append(";font-style:").append(prefs.homeLogoItalic() ? "italic" : "normal").append("}.logo_text{font:inherit}");
        appearance.append(".logo img.smaller{width:").append(logoWidth == 0 ? "auto" : logoWidth + "px")
            .append(";height:").append(logoHeight == 0 ? "auto" : logoHeight + "px")
            .append(";object-fit:cover;border-radius:").append((int)(Math.max(logoWidth, logoHeight) * prefs.homeLogoRadius() / 200f)).append("px}");
        appearance.append(".search_bar{display:").append(prefs.homeSearchVisible() ? "flex" : "none")
                .append(";border-radius:").append((int)((23 + prefs.homeSearchStroke()) * prefs.homeSearchRadius() / 100f))
                .append("px;background:").append((prefs.homeSearchStyle() == 1 || prefs.homeSearchLine() ? "transparent" : cssColor((searchAlpha << 24) | 0x00ffffff))).append(';');
        if (prefs.homeSearchStyle() == 1 || prefs.homeSearchLine() || prefs.homeSearchStroke() > 0) {
            appearance.append(prefs.homeSearchStyle() == 1 || prefs.homeSearchLine() ? "border:0;border-radius:0;border-bottom:" : "border:").append(prefs.homeSearchStroke()).append("px solid ")
                    .append(cssColor((strokeAlpha << 24) | foreground)).append(';');
        } else appearance.append("border:0;");
        if (prefs.homeSearchEffect() == 1) appearance.append("backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);");
        appearance.append("}.box .title{width:").append(favoriteWidth).append("px;height:")
                .append(favoriteHeight).append("px;line-height:").append(favoriteHeight)
                .append("px;border-radius:").append((int)(Math.min(favoriteWidth, favoriteHeight) * prefs.homeFavoriteRadius() / 200f))
                .append("px;color:").append(prefs.homeFavoriteIconColor() == 1 ? (prefs.homeBackgroundDark() ? "white" : "#333") : "white").append(";display:")
                .append(prefs.homeFavoriteIconDisabled() ? "none" : "block").append("}.box .url{display:")
                .append(prefs.homeFavoriteTitleDisabled() ? "none" : "block").append("}");
        appearance.append("#content{top:25%}@media(min-height:250px){#content{top:62px}}@media(min-height:350px){#content{top:87px}}@media(min-height:450px){#content{top:135px}}@media(min-height:650px){#content{top:195px}}@media(min-height:850px){#content{top:255px}}");
        appearance.append("#box_container{display:block;text-align:left;font-size:0}.box{display:inline-block;vertical-align:top;margin:4px 9px;width:")
            .append(favoriteWidth).append("px;height:auto}.box .title{font-size:15px}.box .url{font-size:10px;height:20px;line-height:20px;margin:2px 0 0}");
        int pitch = favoriteWidth + 18;
        for (int columns = 1; columns <= 540 / pitch; columns++) appearance.append("@media(min-width:").append((columns + 1) * pitch)
            .append("px){#box_container{width:").append(columns * pitch).append("px}}");
        appearance.append("#search_submit{display:none}");
        if (!prefs.homeSearchVisible()) appearance.append(".search_part{display:none}#content{top:18px!important}");
        if (prefs.homeBackgroundDark()) appearance.append("body,.logo,.box .url{color:#eee}");
        appearance.append(".search_bar{color:").append(prefs.homeBackgroundDark()
            && (prefs.homeSearchStyle() == 1 || prefs.homeSearchLine() || prefs.homeSearchAlpha() < 50) ? "#fafafa" : "#1b1b1b").append("}");
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
                + "*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;font-family:sans-serif;background:transparent;color:#212121}"
                + "#content{position:relative;top:135px}.search_part{text-align:center;display:table;width:90%;max-width:600px;margin:0 auto 20px}"
                + ".logo{display:inline;text-decoration:none;color:inherit}"
                + ".search_bar{display:flex;width:100%;height:auto;min-height:46px;margin:15px auto 0;border:1px solid rgba(128,128,128,.45);border-radius:23px}"
                + "#search_input{color:inherit;height:46px;padding:0 12px;width:100%;outline:0;border:0;font-size:15px;background:transparent}"
                + "#search_submit{width:50px;border:0;background:transparent;color:inherit;font-size:18px}"
                + "#box_container{display:flex;flex-wrap:wrap;justify-content:center;max-width:600px;margin:auto}.box{position:relative;width:25%;height:92px;text-align:center}"
                + ".box .title{width:48px;height:48px;line-height:48px;border-radius:50%;margin:0 auto 5px;background:#6f8de1;color:white;font-weight:bold}"
                + ".box .url{margin:0 4px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-size:12px;color:#757575}"
                + ".box a{position:absolute;inset:0}" + appearance + customCss + "</style></head><body><div id=\"content\"><div class=\"search_part\">"
                + "<a class=\"logo\" href=\"https://local.via/\">" + logo + "</a>"
                + "<form class=\"search_bar\" onsubmit=\"if(search_input.value.trim()){location.href='via://search?q='+encodeURIComponent(search_input.value);search_input.value='';search_input.blur();}return false\">"
                + "<input class=\"search\" id=\"search_input\" autocomplete=\"off\" enterkeyhint=\"go\"><button id=\"search_submit\" aria-label=\"搜索\">⌕</button></form></div>"
                + "<div id=\"bookmark_part\"><div id=\"box_container\">" + boxes + "</div></div></div><script>document.addEventListener('pointerdown',function(e){if(!e.target.closest('.search_bar')&&document.activeElement)document.activeElement.blur();});</script></body></html>";
    }

    public static int siteColor(String url) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return Color.rgb(Integer.parseInt(hex.substring(0, 3), 16) % 128 + 90,
                Integer.parseInt(hex.substring(13, 16), 16) % 128 + 90,
                Integer.parseInt(hex.substring(29), 16) % 128 + 90);
        } catch (Exception ignored) { return 0xff9a9a9a; }
    }

    private String defaultLogo() {
        try (java.io.InputStream input = context.getAssets().open("via_logo.svg");
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return "<img class=\"smaller\" src=\"data:image/svg+xml;base64," + android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP) + "\">";
        } catch (java.io.IOException e) { return "枢"; }
    }

    private static String html(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String cssColor(int color) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", Color.red(color), Color.green(color),
                Color.blue(color), Color.alpha(color) / 255f);
    }

    private String dataUri(String uri) {
        if (TextUtils.isEmpty(uri)) return "";
        try (java.io.InputStream input = context.getContentResolver().openInputStream(Uri.parse(uri));
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            if (input == null) return "";
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            String type = context.getContentResolver().getType(Uri.parse(uri));
            if (TextUtils.isEmpty(type)) type = "image/*";
            return "data:" + type + ";base64," + android.util.Base64.encodeToString(
                    output.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception ignored) {
            return "";
        }
    }

}
