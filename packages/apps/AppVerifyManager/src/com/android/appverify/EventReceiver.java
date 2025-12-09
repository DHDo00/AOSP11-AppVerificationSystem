package com.android.appverify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class EventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.android.appverify.ACTION_EVENT".equals(intent.getAction())) {
            String pkg = intent.getStringExtra("pkg");
            String type = intent.getStringExtra("type");
            long time = intent.getLongExtra("time", System.currentTimeMillis());
            
            if (pkg != null && type != null) {
                // 保存记录
                EventManager.addEvent(context, pkg, type, time);
                
                // 可选：弹个Toast提示用户
                String msg = "";
                if ("INSTALL".equals(type)) msg = "检测到新安装: " + pkg;
                else if ("UNINSTALL".equals(type)) msg = "检测到卸载: " + pkg;
                else if ("UPDATE".equals(type)) msg = "检测到更新: " + pkg;
                
                Toast.makeText(context, msg + "\n请进入验证管理处理", Toast.LENGTH_LONG).show();
            }
        }
    }
}
