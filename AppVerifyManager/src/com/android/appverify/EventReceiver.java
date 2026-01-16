package com.android.appverify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class EventReceiver extends BroadcastReceiver {

    // 必须和 Server 端保持一致
    private static final String ACTION_AVS_EVENT = "com.android.appverify.ACTION_EVENT";
    private static final String PREF_NAME = "AppVerifyEvents";
    private static final String KEY_EVENTS = "event_list";

    @Override
    public void onReceive(Context context, Intent intent) {
        // 1. 安全检查：只处理我们自定义的 Action
        if (!ACTION_AVS_EVENT.equals(intent.getAction())) return;

        // 2. 提取 Service 传过来的参数
        String pkg = intent.getStringExtra("pkg");
        int type = intent.getIntExtra("type", 0); // 1=Install, 2=Uninstall

        if (pkg == null || type == 0) return;

        Log.i("AppVerifyClient", "Received System Event: " + pkg + " type=" + type);

        // 3. 获取应用名称 (仅安装时尝试获取，卸载时用包名)
        String appName = pkg;
        if (type == 1) {
            try {
                PackageManager pm = context.getPackageManager();
                appName = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
            } catch (Exception e) {
                // 忽略异常，使用包名
            }
        } else {
            appName = pkg + " (已卸载)";
        }

        // 4. 弹出 Toast 提示
        String msg = (type == 1 ? "检测到安装: " : "检测到卸载: ") + appName;
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();

        // 5. 保存数据到本地存储
        saveEvent(context, pkg, appName, type);
    }

    private void saveEvent(Context context, String pkg, String name, int type) {
        // 使用 ApplicationContext 避免潜在泄漏
        SharedPreferences sp = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        String jsonStr = sp.getString(KEY_EVENTS, "[]");
        
        try {
            JSONArray jsonArray = new JSONArray(jsonStr);
            
            JSONObject event = new JSONObject();
            event.put("pkg", pkg);
            event.put("name", name);
            event.put("type", type);
            event.put("time", System.currentTimeMillis());

            // 存入数组
            jsonArray.put(event);

            // 提交保存
            sp.edit().putString(KEY_EVENTS, jsonArray.toString()).apply();
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
