package com.example.cleanrecovery.experimental;

import android.content.Context;
import android.content.SharedPreferences;

/** Separate opt-out and calibration for the global experimental material. */
public final class TiltGlassPrefs {
    public static final int HORIZONTAL=0, READING=1, CUSTOM=2;
    public static final String NAME = "experimental_tilt_glass";
    private final SharedPreferences preferences;

    public TiltGlassPrefs(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(NAME, 0);
    }
    public boolean enabled() { return preferences.getBoolean("enabled", true); }
    public void setEnabled(boolean enabled) { preferences.edit().putBoolean("enabled", enabled).apply(); }
    public int strength() { return Math.max(10, Math.min(100, preferences.getInt("strength", 55))); }
    public void setStrength(int strength) { preferences.edit().putInt("strength", Math.max(10, Math.min(100, strength))).apply(); }
    public float[] reference() {
        if(baselineMode()==HORIZONTAL) return new float[]{0,0,9.81f};
        if(baselineMode()==READING) return TiltGlassModel.readingGravity(readingAngle());
        return new float[]{preferences.getFloat("x", 0), preferences.getFloat("y", 0), preferences.getFloat("z", 9.81f)};
    }
    public int readingAngle() { return Math.max(0,Math.min(90,preferences.getInt("reading_angle",45))); }
    public void setReadingAngle(int angle) {
        preferences.edit().putInt("reading_angle",Math.max(0,Math.min(90,angle)))
                .putInt("baseline_mode",READING).apply();
    }
    public int baselineMode() {
        int mode=preferences.getInt("baseline_mode",calibrated()?CUSTOM:READING);
        return mode==HORIZONTAL || mode==READING || (mode==CUSTOM && calibrated()) ? mode : READING;
    }
    public void setBaselineMode(int mode) {
        if(mode==HORIZONTAL || mode==READING || (mode==CUSTOM && calibrated()))
            preferences.edit().putInt("baseline_mode",mode).apply();
    }
    public String baselineLabel() {
        return baselineMode()==HORIZONTAL ? "水平基准 · 0°" : baselineMode()==READING ? "角度基准 · "+readingAngle()+"°" : "自定义基准";
    }
    public boolean calibrated() { return preferences.getBoolean("calibrated", false); }
    public void calibrate(float x, float y, float z) {
        preferences.edit().putInt("baseline_mode",CUSTOM).putFloat("x", x).putFloat("y", y).putFloat("z", z).putBoolean("calibrated", true).remove("orientation_calibrated").apply();
    }
    public boolean hasOrientationReference() { return preferences.getBoolean("orientation_calibrated", false); }
    public float[] orientationReference() {
        return new float[]{preferences.getFloat("nx",0),preferences.getFloat("ny",0),preferences.getFloat("nz",1)};
    }
    public void calibrateOrientation(float[] matrix) {
        preferences.edit().putInt("baseline_mode",CUSTOM).putFloat("x",matrix[6]*9.81f).putFloat("y",matrix[7]*9.81f)
                .putFloat("z",matrix[8]*9.81f).putBoolean("calibrated",true)
                .putFloat("nx",matrix[2]).putFloat("ny",matrix[5]).putFloat("nz",matrix[8])
                .putBoolean("orientation_calibrated",true).apply();
    }
    public void resetReference() {
        setBaselineMode(HORIZONTAL);
    }
}
