package com.android.appverify;

import android.app.Activity;
import android.app.IAppVerificationManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReviewActivity extends Activity {

    private static final String TAG = "AppVerifyReview"; // 调试日志 TAG
    private ListView mListView;
    private IAppVerificationManager mService;
    private List<EventManager.AppEvent> mEvents;
    private EventAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        // 初始化服务连接
        connectToService();

        mListView = findViewById(R.id.listReview);
        Button btnProcess = findViewById(R.id.btnProcess);

        loadEvents();

        btnProcess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processSelectedEvents();
            }
        });
    }

    private void connectToService() {
        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));
        if (mService == null) {
            Log.e(TAG, "Fatal: Service connection failed in onCreate.");
        } else {
            Log.d(TAG, "Service connected successfully.");
        }
    }

    private void loadEvents() {
        mEvents = EventManager.getEvents(this);
        mAdapter = new EventAdapter(this, mEvents);
        mListView.setAdapter(mAdapter);
        if (mEvents.isEmpty()) {
            Toast.makeText(this, "暂无待处理事件", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "Loaded " + mEvents.size() + " pending events.");
        }
    }

    /**
     * 核心处理逻辑 (带调试日志版)
     */
    private void processSelectedEvents() {
        Log.d(TAG, ">>> 用户点击同步按钮，开始处理...");

        // 1. 检查服务连接
        if (mService == null) {
            Log.w(TAG, "Service is null, trying to reconnect...");
            connectToService();
            if (mService == null) {
                Log.e(TAG, "ERROR: 无法连接到 app_verification 服务，终止操作。");
                Toast.makeText(this, "服务未连接，无法同步", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        int count = 0;
        // 倒序遍历，方便删除
        for (int i = mEvents.size() - 1; i >= 0; i--) {
            EventManager.AppEvent event = mEvents.get(i);
            
            Log.d(TAG, "--------------------------------------------------");
            Log.d(TAG, "正在处理第 " + (i+1) + " 项: [" + event.pkg + "] 类型: [" + event.type + "]");

            try {
                if ("INSTALL".equals(event.type) || "UPDATE".equals(event.type)) {
                    Log.d(TAG, "-> 动作匹配: 添加/更新白名单");
                    Log.d(TAG, "-> 调用 AIDL: mService.addToWhitelist(" + event.pkg + ")");
                    
                    mService.addToWhitelist(event.pkg);
                    
                    Log.d(TAG, "-> AIDL 调用完成 (无异常)");
                    
                } else if ("UNINSTALL".equals(event.type)) {
                    Log.d(TAG, "-> 动作匹配: 移除白名单");
                    Log.d(TAG, "-> 调用 AIDL: mService.removeFromWhitelist(" + event.pkg + ")");
                    
                    boolean result = mService.removeFromWhitelist(event.pkg);
                    Log.d(TAG, "-> AIDL 调用完成，返回值: " + result);
                    
                } else {
                    Log.w(TAG, "-> 未知事件类型: " + event.type + "，跳过处理。");
                }
                
                // 处理完后从本地记录移除
                EventManager.clearEvent(this, event.pkg);
                count++;
                
            } catch (RemoteException e) {
                Log.e(TAG, "!!! AIDL 远程调用失败: " + event.pkg, e);
                e.printStackTrace();
            } catch (Exception e) {
                Log.e(TAG, "!!! 未知错误: ", e);
            }
        }
        
        Log.d(TAG, ">>> 批处理结束，共处理: " + count + " 条");
        Toast.makeText(this, "已同步处理 " + count + " 条记录", Toast.LENGTH_SHORT).show();
        loadEvents(); // 刷新列表
    }

    // 内部 Adapter
    class EventAdapter extends ArrayAdapter<EventManager.AppEvent> {
        public EventAdapter(Activity context, List<EventManager.AppEvent> items) {
            super(context, android.R.layout.simple_list_item_2, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = android.view.LayoutInflater.from(getContext())
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            EventManager.AppEvent item = getItem(position);
            TextView t1 = convertView.findViewById(android.R.id.text1);
            TextView t2 = convertView.findViewById(android.R.id.text2);

            String actionText = "";
            if ("INSTALL".equals(item.type)) actionText = "[新安装] 建议添加";
            else if ("UPDATE".equals(item.type)) actionText = "[更新] 建议更新签名";
            else if ("UNINSTALL".equals(item.type)) actionText = "[卸载] 建议移除";
            else actionText = "[未知] " + item.type;

            t1.setText(actionText);
            t2.setText(item.pkg + "\n" + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(item.time)));
            return convertView;
        }
    }
}
