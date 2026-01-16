package com.android.appverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.IAppVerificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable; // 必须导入这个
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private IAppVerificationManager mService;
    private TextView mTvStatus;
    private Switch mSwitch;
    private ListView mListView;
    private TextView mTvEmpty;
    private WhitelistAdapter mAdapter;
    private List<AppItem> mData = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 获取系统服务
        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        // 2. 初始化控件
        mTvStatus = findViewById(R.id.tvStatus);
        mSwitch = findViewById(R.id.switchProtection);
        mListView = findViewById(R.id.listWhitelist);
        mTvEmpty = findViewById(R.id.tvEmpty);
        Button btnScan = findViewById(R.id.btnScan);
        Button btnEvents = findViewById(R.id.btnEvents);

        // 3. 设置列表适配器
        mAdapter = new WhitelistAdapter();
        mListView.setAdapter(mAdapter);

        // 4. 开关监听：切换保护模式
        mSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateStatusUI(isChecked);
            if (mService != null) {
                try {
                    mService.setVerificationMode(isChecked);
                } catch (RemoteException e) {
                    e.printStackTrace();
                    Toast.makeText(this, "服务通信失败", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 5. 底部按钮监听
        btnScan.setOnClickListener(v -> {
            startActivity(new Intent(this, AppSelectionActivity.class));
        });

        btnEvents.setOnClickListener(v -> {
            startActivity(new Intent(this, ReviewActivity.class));
        });

        // 6. 列表长按删除
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            showDeleteDialog(mData.get(position));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshData();
    }

    private void refreshData() {
        if (mService == null) return;

        try {
            // 1. 刷新开关状态
            boolean enabled = mService.getVerificationMode();
            mSwitch.setChecked(enabled);
            updateStatusUI(enabled);

            // 2. 刷新白名单列表
            mData.clear();
            Map whitelist = mService.getWhitelist();
            if (whitelist != null) {
                PackageManager pm = getPackageManager();
                for (Object key : whitelist.keySet()) {
                    String pkg = (String) key;
                    AppItem item = new AppItem();
                    item.pkg = pkg;
                    
                    // 获取应用名称和图标
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        item.name = ai.loadLabel(pm).toString();
                        item.icon = ai.loadIcon(pm); // 【关键修复】加载图标
                        item.isUninstalled = false;
                    } catch (PackageManager.NameNotFoundException e) {
                        item.name = pkg + " (已卸载)";
                        item.isUninstalled = true;
                        item.icon = null; // 已卸载则无图标
                    }
                    mData.add(item);
                }
            }
            mAdapter.notifyDataSetChanged();
            
            // 处理空状态显示
            if (mData.isEmpty()) {
                mTvEmpty.setVisibility(View.VISIBLE);
                mListView.setVisibility(View.GONE);
            } else {
                mTvEmpty.setVisibility(View.GONE);
                mListView.setVisibility(View.VISIBLE);
            }

        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void updateStatusUI(boolean enabled) {
        if (enabled) {
            mTvStatus.setText(R.string.mode_enabled);
            mTvStatus.setTextColor(Color.parseColor("#4CAF50")); // 绿色
        } else {
            mTvStatus.setText(R.string.mode_disabled);
            mTvStatus.setTextColor(Color.parseColor("#F44336")); // 红色
        }
    }

    private void showDeleteDialog(AppItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage("确定要移除 " + item.name + " 吗？")
                .setPositiveButton(R.string.btn_confirm, (d, w) -> {
                    try {
                        if (mService != null) {
                            mService.removeAppFromWhitelist(item.pkg);
                            refreshData(); // 刷新列表
                            Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    // ==========================================
    // 内部类：数据模型 (修复了这里)
    // ==========================================
    class AppItem {
        String name;
        String pkg;
        boolean isUninstalled;
        Drawable icon; // 【关键修复】添加了 icon 变量
    }

    // ==========================================
    // 内部类：适配器
    // ==========================================
    class WhitelistAdapter extends BaseAdapter {
        @Override
        public int getCount() { return mData.size(); }
        @Override
        public Object getItem(int position) { return mData.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // 加载带图标的布局
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_whitelist, parent, false);
            }

            ImageView iconView = convertView.findViewById(R.id.imgIcon);
            TextView nameView = convertView.findViewById(R.id.tvName);
            TextView pkgView = convertView.findViewById(R.id.tvPkg);

            AppItem item = mData.get(position);
            
            nameView.setText(item.name);
            pkgView.setText(item.pkg);

            // 显示图标
            if (item.icon != null) {
                iconView.setImageDrawable(item.icon);
            } else {
                iconView.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            // 如果是已卸载的应用，让它变灰
            if (item.isUninstalled) {
                nameView.setTextColor(Color.GRAY);
                iconView.setAlpha(0.5f);
            } else {
                nameView.setTextColor(Color.BLACK);
                iconView.setAlpha(1.0f);
            }
            
            return convertView;
        }
    }
}
