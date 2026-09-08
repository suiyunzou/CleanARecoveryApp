package com.example.cleanrecovery.ui.browser;

import org.json.JSONObject;

/** Page-owned callbacks, discarded naturally when the document navigates. */
public final class BrowserScriptMenus {
    private final String registry = "__via_menus_" + java.util.UUID.randomUUID().toString().replace("-", "");

    public String script(String name) {
        return "var __registry=window[" + JSONObject.quote(registry) + "];"
                + "if(!__registry){__registry={epoch:String(Date.now())+Math.random(),scripts:Object.create(null)};window["
                + JSONObject.quote(registry) + "]=__registry}"
                + "var __menu=__registry.scripts[" + JSONObject.quote(name) + "];"
                + "if(!__menu)__menu=__registry.scripts[" + JSONObject.quote(name) + "]=Object.create(null);"
                + "var GM_registerMenuCommand=function(name,func,key){if(typeof func!=='function')return;name=String(name);__menu[name]=func;return name};"
                + "var GM_unregisterMenuCommand=function(name){if(name)delete __menu[String(name)]};";
    }

    public String list(String name) {
        return "(function(){var r=window[" + JSONObject.quote(registry) + "];return {epoch:r?r.epoch:'',names:r&&r.scripts["
                + JSONObject.quote(name) + "]?Object.keys(r.scripts[" + JSONObject.quote(name) + "]):[]}})()";
    }

    public String invoke(String name, String command, String epoch) {
        return "(function(){var r=window[" + JSONObject.quote(registry) + "];if(!r||r.epoch!=="
                + JSONObject.quote(epoch) + ")return;var m=r.scripts[" + JSONObject.quote(name)
                + "];if(m&&typeof m[" + JSONObject.quote(command) + "]==='function')m["
                + JSONObject.quote(command) + "]()})()";
    }
}
