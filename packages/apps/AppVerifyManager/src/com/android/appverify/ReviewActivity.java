package com.android.appverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.IAppVerificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReviewActivity extends Activity {

    private static final String PREF_NAME = "AppVerifyEvents";
    private static final String KEY_EVENTS = "event_list";

    private IAppVerificationManager mService; // 增加服务连接
    private ListView mListView;
    private View mLayoutEmpty;
    private EventAdapter mAdapter;
    private List<EventItem> mData = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        // 连接服务，用于后续操作白名单
        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        initViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadEvents();
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        mListView = findViewById(R.id.listEvents);
        mLayoutEmpty = findViewById(R.id.layoutEmpty);
        mAdapter = new EventAdapter();
        mListView.setAdapter(mAdapter);

        // 【核心功能】点击列表项，查看详情并处理
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            EventItem item = mData.get(position);
            showActionDialog(item);
        });

        findViewById(R.id.btnClear).setOnClickListener(v -> clearEvents());
    }

    /**
     * 显示详情与操作弹窗
     */
    private void showActionDialog(EventItem item) {
        String versionStr = "未知 (已卸载或无法获取)";
        
        // 尝试获取版本号
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(item.pkg, 0);
            versionStr = pi.versionName + " (" + pi.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            // 如果是卸载事件，应用可能已经不在了，这是正常的
        }

        String message = "应用名称: " + item.name + "\n" +
                         "应用包名: " + item.pkg + "\n" +
                         "当前版本: " + versionStr + "\n\n" +
                         "事件类型: " + (item.type == 1 ? "应用安装" : "应用卸载");

        String btnText;
        // 根据事件类型决定按钮功能
        if (item.type == 1) {
            btnText = "加入受保护列表"; // 安装 -> 建议加入
        } else {
            btnText = "从受保护列表移除"; // 卸载 -> 建议移除
        }

        new AlertDialog.Builder(this)
                .setTitle("事件详情与处置")
                .setMessage(message)
                .setPositiveButton(btnText, (dialog, which) -> {
                    processEventAction(item);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 执行添加或移除白名单的操作
     */
    private void processEventAction(EventItem item) {
        if (mService == null) {
            Toast.makeText(this, "服务未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (item.type == 1) {
                // 安装事件 -> 加白
                mService.addAppToWhitelist(item.pkg);
                Toast.makeText(this, "已加入受保护列表", Toast.LENGTH_SHORT).show();
            } else {
                // 卸载事件 -> 移出
                mService.removeAppFromWhitelist(item.pkg);
                Toast.makeText(this, "已从受保护列表移除", Toast.LENGTH_SHORT).show();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
            Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadEvents() {
        mData.clear();
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String jsonStr = sp.getString(KEY_EVENTS, "[]");

        try {
            JSONArray jsonArray = new JSONArray(jsonStr);
            for (int i = jsonArray.length() - 1; i >= 0; i--) {
                JSONObject obj = jsonArray.getJSONObject(i);
                EventItem item = new EventItem();
                item.pkg = obj.optString("pkg");
                item.name = obj.optString("name");
                item.type = obj.optInt("type");
                item.time = obj.optLong("time");
                mData.add(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        mAdapter.notifyDataSetChanged();
        
        if (mData.isEmpty()) {
            mLayoutEmpty.setVisibility(View.VISIBLE);
            mListView.setVisibility(View.GONE);
        } else {
            mLayoutEmpty.setVisibility(View.GONE);
            mListView.setVisibility(View.VISIBLE);
        }
    }

    private void clearEvents() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_EVENTS).apply();
        loadEvents();
        Toast.makeText(this, "记录已清除", Toast.LENGTH_SHORT).show();
    }

    // ==========================================
    
    static class EventItem {
        String name;
        String pkg;
        int type; // 1=Install, 2=Remove
        long time;
    }

    class EventAdapter extends BaseAdapter {
        private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        @Override public int getCount() { return mData.size(); }
        @Override public Object getItem(int position) { return mData.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ReviewActivity.this)
                        .inflate(R.layout.item_event, parent, false);
            }

            EventItem item = mData.get(position);

            TextView tvName = convertView.findViewById(R.id.tvAppName);
            TextView tvPkg = convertView.findViewById(R.id.tvPkgName);
            TextView tvTime = convertView.findViewById(R.id.tvTime);
            TextView tvType = convertView.findViewById(R.id.tvType);
            ImageView imgStatus = convertView.findViewById(R.id.imgStatus);

            tvName.setText(item.name);
            tvPkg.setText(item.pkg);
            tvTime.setText(sdf.format(new Date(item.time)));

            if (item.type == 1) {
                tvType.setText("安装");
                tvType.setTextColor(0xFF4CAF50);
                tvType.setBackgroundColor(0x104CAF50); // 浅绿色背景
                imgStatus.setImageResource(android.R.drawable.stat_sys_download);
                imgStatus.setColorFilter(0xFF4CAF50);
            } else {
                tvType.setText("卸载");
                tvType.setTextColor(0xFFFF5722);
                tvType.setBackgroundColor(0x10FF5722); // 浅橙色背景
                imgStatus.setImageResource(android.R.drawable.ic_delete);
                imgStatus.setColorFilter(0xFFFF5722);
            }

            return convertView;
        }
    }
}
