package com.example.cleanrecovery.experimental;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, app-private event metadata. Never stores audio. */
public final class LabHistory {
    private LabHistory() { }
    public static synchronized JSONArray read(Context context) {
        try { return new JSONArray(context.getSharedPreferences("experimental_history", 0)
                .getString("events", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
    public static synchronized void add(Context context, String message) {
        JSONArray old = read(context), next = new JSONArray();
        try {
            next.put(new JSONObject().put("time", System.currentTimeMillis()).put("message", message));
            for (int i=0; i<Math.min(199, old.length()); i++) next.put(old.get(i));
            context.getSharedPreferences("experimental_history", 0).edit()
                    .putString("events", next.toString()).apply();
        } catch (Exception ignored) { }
    }
    public static synchronized void clear(Context context) {
        context.getSharedPreferences("experimental_history", 0).edit().remove("events").apply();
    }
}
