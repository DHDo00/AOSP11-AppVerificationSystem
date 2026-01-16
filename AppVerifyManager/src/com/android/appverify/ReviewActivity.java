package com.android.appverify;

import android.app.Activity;
import android.app.IAppVerificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager; // 补上
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ReviewActivity extends Activity {

    private static final String PREF_NAME = "AppVerifyEvents";
    private static final String KEY_EVENTS = "event_list";

    private IAppVerificationManager mService;
    private ListView mListView;
    private ReviewAdapter mAdapter;
    private List<EventItem> mEvents = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        TextView tvTitle = findViewById(R.id.tvPageTitle);
        if (tvTitle != null) tvTitle.setText(R.string.btn_review_events);

        mListView = findViewById(R.id.listReview);
        mAdapter = new ReviewAdapter();
        mListView.setAdapter(mAdapter);

        findViewById(R.id.btnSelectAll).setOnClickListener(v -> {
            boolean select = !isAllSelected();
            for (EventItem item : mEvents) item.isChecked = select;
            mAdapter.notifyDataSetChanged();
        });

        findViewById(R.id.btnProcess).setOnClickListener(v -> processEvents());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadEvents(); // 每次进入页面读取最新数据
    }

    private void loadEvents() {
        mEvents.clear();
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String jsonStr = sp.getString(KEY_EVENTS, "[]");

        try {
            JSONArray jsonArray = new JSONArray(jsonStr);
            // 倒序遍历，让最新的显示在最上面
            for (int i = jsonArray.length() - 1; i >= 0; i--) {
                JSONObject obj = jsonArray.getJSONObject(i);
                EventItem item = new EventItem();
                item.pkg = obj.getString("pkg");
                item.name = obj.getString("name");
                item.type = obj.getInt("type");
                item.timestamp = obj.getLong("time");
                mEvents.add(item);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mAdapter.notifyDataSetChanged();
    }

    private void processEvents() {
        int processed = 0;
        List<EventItem> toRemove = new ArrayList<>();

        // 1. 处理选中的事件 (加入/移出白名单)
        for (EventItem item : mEvents) {
            if (item.isChecked) {
                try {
                    if (mService != null) {
                        if (item.type == 1) { // Added
                            mService.addAppToWhitelist(item.pkg);
                        } else { // Removed
                            mService.removeAppFromWhitelist(item.pkg);
                        }
                    }
                    toRemove.add(item);
                    processed++;
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        // 2. 从列表中移除已处理的项
        mEvents.removeAll(toRemove);
        mAdapter.notifyDataSetChanged();

        // 3. 更新本地存储 (把剩下的存回去)
        saveRemainingEvents();

        Toast.makeText(this, "已处理 " + processed + " 项", Toast.LENGTH_SHORT).show();
    }

    private void saveRemainingEvents() {
        // 将 mEvents 转回 JSON 存入 SharedPreferences
        JSONArray jsonArray = new JSONArray();
        try {
            // 注意：因为 mEvents 是倒序显示的，存回去的时候最好正序存，或者保持一致
            // 这里为了简单，直接按当前顺序存
            for (int i = mEvents.size() - 1; i >= 0; i--) {
                EventItem item = mEvents.get(i);
                JSONObject obj = new JSONObject();
                obj.put("pkg", item.pkg);
                obj.put("name", item.name);
                obj.put("type", item.type);
                obj.put("time", item.timestamp);
                jsonArray.put(obj);
            }
            
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_EVENTS, jsonArray.toString()).apply();
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private boolean isAllSelected() {
        for (EventItem i : mEvents) if (!i.isChecked) return false;
        return !mEvents.isEmpty();
    }

    static class EventItem {
        String pkg;
        String name;
        int type; // 1=Add, 2=Remove
        long timestamp;
        boolean isChecked;
    }

    class ReviewAdapter extends BaseAdapter {
        @Override
        public int getCount() { return mEvents.size(); }
        @Override
        public Object getItem(int i) { return mEvents.get(i); }
        @Override
        public long getItemId(int i) { return i; }
        @Override
        public View getView(int i, View v, ViewGroup p) {
            if (v == null) v = LayoutInflater.from(ReviewActivity.this)
                    .inflate(R.layout.item_review, p, false);
            
            EventItem item = mEvents.get(i);
            TextView tvPkg = v.findViewById(R.id.tvPkg);
            TextView tvReason = v.findViewById(R.id.tvReason);
            CheckBox cb = v.findViewById(R.id.cbSelect);

            tvPkg.setText(item.name);
            
            if (item.type == 1) {
                tvReason.setText("新安装应用 (" + item.pkg + ")");
                tvReason.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            } else {
                tvReason.setText("应用已卸载 (" + item.pkg + ")");
                tvReason.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            }
            
            cb.setChecked(item.isChecked);
            
            View.OnClickListener toggle = view -> {
                item.isChecked = !item.isChecked;
                notifyDataSetChanged();
            };
            
            v.setOnClickListener(toggle);
            cb.setOnClickListener(toggle);

            return v;
        }
    }
}
