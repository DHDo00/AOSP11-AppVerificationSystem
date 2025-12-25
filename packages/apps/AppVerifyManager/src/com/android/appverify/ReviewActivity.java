package com.android.appverify;

import android.app.Activity;
import android.app.IAppVerificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReviewActivity extends Activity {

    private static final String TAG = "AppVerifyReview";
    private ListView mListView;
    private IAppVerificationManager mService;
    private ReviewAdapter mAdapter;
    private List<EventItem> mEventItems = new ArrayList<>();
    private boolean mIsAllSelected = false;

    // 视图模型：包含 UI 显示所需的所有信息
    private static class EventItem {
        EventManager.AppEvent rawEvent;
        String appName;
        Drawable icon;
        boolean isChecked;

        public EventItem(EventManager.AppEvent rawEvent) {
            this.rawEvent = rawEvent;
            this.isChecked = false; // 默认不勾选
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        connectToService();

        mListView = findViewById(R.id.listReview);
        Button btnProcess = findViewById(R.id.btnProcess);
        Button btnSelectAll = findViewById(R.id.btnSelectAll);

        mAdapter = new ReviewAdapter(this, mEventItems);
        mListView.setAdapter(mAdapter);

        // 点击条目切换勾选状态
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            EventItem item = mEventItems.get(position);
            item.isChecked = !item.isChecked;
            mAdapter.notifyDataSetChanged();
        });

        // 全选/反选 按钮
        btnSelectAll.setOnClickListener(v -> {
            mIsAllSelected = !mIsAllSelected;
            for (EventItem item : mEventItems) {
                item.isChecked = mIsAllSelected;
            }
            mAdapter.notifyDataSetChanged();
        });

        // 处理按钮
        btnProcess.setOnClickListener(v -> processSelectedEvents());

        loadEvents();
    }

    private void connectToService() {
        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));
    }

    private void loadEvents() {
        final List<EventManager.AppEvent> rawEvents = EventManager.getEvents(this);
        
        if (rawEvents.isEmpty()) {
            Toast.makeText(this, "暂无待处理事件", Toast.LENGTH_SHORT).show();
            return;
        }

        // 异步加载图标和名称，防止卡顿
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<EventItem> loadedItems = new ArrayList<>();

            for (EventManager.AppEvent event : rawEvents) {
                EventItem item = new EventItem(event);
                
                // 处理图标和名称
                if ("UNINSTALL".equals(event.type)) {
                    // 已卸载的应用，无法获取图标和名称，只能显示包名
                    item.appName = event.pkg + " (已卸载)";
                    item.icon = getDrawable(android.R.drawable.sym_def_app_icon); // 默认图标
                } else {
                    // 安装或更新，尝试获取真实信息
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(event.pkg, 0);
                        item.appName = pm.getApplicationLabel(ai).toString();
                        item.icon = pm.getApplicationIcon(ai);
                    } catch (PackageManager.NameNotFoundException e) {
                        // 可能发生的情况：刚收到安装通知，但解析时又卸载了，或者获取失败
                        item.appName = event.pkg + " (未知)";
                        item.icon = getDrawable(android.R.drawable.sym_def_app_icon);
                    }
                }
                loadedItems.add(item);
            }

            runOnUiThread(() -> {
                mEventItems.clear();
                mEventItems.addAll(loadedItems);
                mAdapter.notifyDataSetChanged();
                Log.d(TAG, "Loaded " + mEventItems.size() + " items.");
            });
        }).start();
    }

    private void processSelectedEvents() {
        if (mService == null) connectToService();
        if (mService == null) {
            Toast.makeText(this, "服务未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> processedPackages = new ArrayList<>();
        int count = 0;

        for (EventItem item : mEventItems) {
            if (!item.isChecked) continue; // 只处理勾选的

            String pkg = item.rawEvent.pkg;
            String type = item.rawEvent.type;

            try {
                if ("INSTALL".equals(type) || "UPDATE".equals(type)) {
                    Log.d(TAG, "Adding: " + pkg);
                    mService.addToWhitelist(pkg);
                } else if ("UNINSTALL".equals(type)) {
                    Log.d(TAG, "Removing: " + pkg);
                    mService.removeFromWhitelist(pkg);
                }
                processedPackages.add(pkg);
                count++;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to process " + pkg, e);
            }
        }

        if (count > 0) {
            // 批量从数据库移除记录
            EventManager.removeEvents(this, processedPackages);
            Toast.makeText(this, "已同步处理 " + count + " 个应用", Toast.LENGTH_SHORT).show();
            loadEvents(); // 重新加载列表
        } else {
            Toast.makeText(this, "请先勾选需要处理的事件", Toast.LENGTH_SHORT).show();
        }
    }

    // 自定义 Adapter
    private class ReviewAdapter extends BaseAdapter {
        private Context context;
        private List<EventItem> items;
        private LayoutInflater inflater;

        public ReviewAdapter(Context context, List<EventItem> items) {
            this.context = context;
            this.items = items;
            this.inflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() { return items.size(); }
        @Override
        public Object getItem(int position) { return items.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_event, parent, false);
                holder = new ViewHolder();
                holder.checkBox = convertView.findViewById(R.id.cbSelect);
                holder.icon = convertView.findViewById(R.id.imgIcon);
                holder.tvTitle = convertView.findViewById(R.id.tvTitle);
                holder.tvSubtitle = convertView.findViewById(R.id.tvSubtitle);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            EventItem item = items.get(position);

            // 设置复选框
            holder.checkBox.setChecked(item.isChecked);

            // 设置图标
            holder.icon.setImageDrawable(item.icon);

            // 构造标题：[类型] 应用名
            String typeStr = "";
            int typeColor = 0xFF000000; // 默认黑色
            
            if ("INSTALL".equals(item.rawEvent.type)) {
                typeStr = "[新安装] ";
                typeColor = 0xFF4CAF50; // 绿色
            } else if ("UPDATE".equals(item.rawEvent.type)) {
                typeStr = "[更新] ";
                typeColor = 0xFF2196F3; // 蓝色
            } else if ("UNINSTALL".equals(item.rawEvent.type)) {
                typeStr = "[卸载] ";
                typeColor = 0xFFF44336; // 红色
            }

            holder.tvTitle.setText(typeStr + item.appName);
            // 这里可以简单用 SpannableString 给前缀上色，为了简化代码未展示

            // 构造副标题：包名 | 时间
            String dateStr = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(item.rawEvent.time));
            holder.tvSubtitle.setText(item.rawEvent.pkg + "  |  " + dateStr);

            return convertView;
        }

        class ViewHolder {
            CheckBox checkBox;
            ImageView icon;
            TextView tvTitle;
            TextView tvSubtitle;
        }
    }
}
