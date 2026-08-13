package com.g10blelab.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/** Small, merge-only JSON backup for settings, learned battery data and history. */
public final class AppDataBackup {

    private static final int SCHEMA_VERSION = 1;

    private AppDataBackup() {
    }

    public static String exportJson(Context context) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA_VERSION);
        root.put("app", "G10 Companion");
        root.put("created_at_ms", System.currentTimeMillis());
        root.put("battery_ai", preferencesToJson(
                context.getSharedPreferences("g10_battery_ai", Context.MODE_PRIVATE)
        ));
        root.put("trips", preferencesToJson(
                context.getSharedPreferences("g10_trips", Context.MODE_PRIVATE)
        ));
        return root.toString(2);
    }

    public static void importJson(Context context, String text) throws JSONException {
        JSONObject root = new JSONObject(text == null ? "" : text);
        int schema = root.optInt("schema", -1);
        if (schema != SCHEMA_VERSION) {
            throw new JSONException("Неподдерживаемая версия резервной копии: " + schema);
        }

        JSONObject battery = root.optJSONObject("battery_ai");
        JSONObject trips = root.optJSONObject("trips");
        if (battery == null || trips == null) {
            throw new JSONException("В резервной копии нет обязательных разделов");
        }

        // Merge instead of clear: future application keys are not destroyed by
        // restoring an older, valid backup.
        restorePreferences(
                context.getSharedPreferences("g10_battery_ai", Context.MODE_PRIVATE),
                battery
        );
        restorePreferences(
                context.getSharedPreferences("g10_trips", Context.MODE_PRIVATE),
                trips
        );
    }

    private static JSONObject preferencesToJson(SharedPreferences preferences)
            throws JSONException {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String || value instanceof Boolean ||
                    value instanceof Integer || value instanceof Long ||
                    value instanceof Float) {
                json.put(entry.getKey(), value);
            }
        }
        return json;
    }

    private static void restorePreferences(
            SharedPreferences preferences,
            JSONObject json
    ) throws JSONException {
        SharedPreferences.Editor editor = preferences.edit();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Number) {
                editor.putFloat(key, ((Number) value).floatValue());
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            }
        }
        editor.apply();
    }
}
