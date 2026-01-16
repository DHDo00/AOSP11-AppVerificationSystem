package com.android.appverify;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class EventManager {
    private static final String PREF_NAME = "app_events";
    private static final String KEY_EVENTS = "pending_events";

    public static class AppEvent {
        public String pkg;
        public String type; // INSTALL, UNINSTALL, UPDATE
        public long time;
        
        public AppEvent(String pkg, String type, long time) {
            this.pkg = pkg;
            this.type = type;
            this.time = time;
        }
    }

    public static void addEvent(Context context, String pkg, String type, long time) {
        List<AppEvent> events = getEvents(context);
        // 简单去重：如果已有该包的同类型事件，先移除旧的
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).pkg.equals(pkg)) {
                events.remove(i);
            }
        }
        events.add(0, new AppEvent(pkg, type, time)); // 加到最前面
        saveEvents(context, events);
    }

    public static List<AppEvent> getEvents(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String jsonStr = sp.getString(KEY_EVENTS, "[]");
        List<AppEvent> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new AppEvent(obj.getString("p"), obj.getString("t"), obj.getLong("m")));
            }
        } catch (JSONException e) { e.printStackTrace(); }
        return list;
    }

    public static void saveEvents(Context context, List<AppEvent> events) {
        try {
            JSONArray arr = new JSONArray();
            for (AppEvent e : events) {
                JSONObject obj = new JSONObject();
                obj.put("p", e.pkg);
                obj.put("t", e.type);
                obj.put("m", e.time);
                arr.put(obj);
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_EVENTS, arr.toString()).apply();
        } catch (JSONException e) { e.printStackTrace(); }
    }
    
    public static void clearEvent(Context context, String pkg) {
        List<AppEvent> events = getEvents(context);
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).pkg.equals(pkg)) {
                events.remove(i);
                break;
            }
        }
        saveEvents(context, events);
    }
    /**
     * [新增] 批量移除事件 (性能优化)
     */
    public static void removeEvents(Context context, List<String> packagesToRemove) {
        if (packagesToRemove == null || packagesToRemove.isEmpty()) return;

        List<AppEvent> events = getEvents(context);
        List<AppEvent> newEvents = new ArrayList<>();

        // 保留不在移除列表中的事件
        for (AppEvent e : events) {
            if (!packagesToRemove.contains(e.pkg)) {
                newEvents.add(e);
            }
        }

        saveEvents(context, newEvents);
    }
}
