package com.android.appverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.IAppVerificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
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
    private Switch mSwitch;
    private TextView mTvStatus;
    private ListView mListView;
    private View mLayoutEmpty;
    private EditText mEtSearch;
    private WhitelistAdapter mAdapter;

    private List<AppItem> mAllData = new ArrayList<>();
    private List<AppItem> mDisplayData = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        initViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        loadWhitelist();
    }

    private void initViews() {
        // 1. 顶部开关
        mSwitch = findViewById(R.id.switchProtection);
        mTvStatus = findViewById(R.id.tvStatus);
        
        mSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            try {
                if (mService != null) {
                    mService.setVerificationMode(isChecked);
                    refreshStatus();
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });

        // 2. 搜索框
        mEtSearch = findViewById(R.id.etSearch);
        mEtSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { filterList(s.toString()); }
        });

        // 3. 列表初始化
        mListView = findViewById(R.id.listWhitelist);
        mLayoutEmpty = findViewById(R.id.layoutEmpty);
        mAdapter = new WhitelistAdapter();
        mListView.setAdapter(mAdapter);

        // 【新增】点击查看详情
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            AppItem item = mDisplayData.get(position);
            showAppInfoDialog(item);
        });

        // 【新增】长按删除
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            AppItem item = mDisplayData.get(position);
            showDeleteConfirmDialog(item);
            return true;
        });

        // 4. 底部按钮
        findViewById(R.id.btnScanCard).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, AppSelectionActivity.class));
        });

        findViewById(R.id.btnReviewCard).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, ReviewActivity.class));
        });
    }

    private void showAppInfoDialog(AppItem item) {
        new AlertDialog.Builder(this)
                .setTitle("应用信息")
                .setMessage("名称: " + item.name + "\n包名: " + item.pkg + "\n\n该应用已在可信白名单中。")
                .setPositiveButton("确定", null)
                .show();
    }

    private void showDeleteConfirmDialog(AppItem item) {
        new AlertDialog.Builder(this)
                .setTitle("移除保护")
                .setMessage("确定要将 " + item.name + " 从可信白名单中移除吗？\n移除后该应用可能无法启动。")
                .setPositiveButton("移除", (dialog, which) -> {
                    removeApp(item.pkg);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void removeApp(String pkg) {
        try {
            if (mService != null) {
                mService.removeAppFromWhitelist(pkg);
                Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
                loadWhitelist(); // 刷新列表
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void refreshStatus() {
        try {
            if (mService != null) {
                boolean enabled = mService.getVerificationMode();
                mSwitch.setChecked(enabled);
                mTvStatus.setText(enabled ? "保护状态：已开启" : "保护状态：已关闭");
                mTvStatus.setTextColor(enabled ? 0xFF4CAF50 : 0xFF999999);
            }
        } catch (RemoteException e) { e.printStackTrace(); }
    }

    private void loadWhitelist() {
        mAllData.clear();
        try {
            if (mService != null) {
                Map whitelist = mService.getWhitelist();
                if (whitelist != null) {
                    PackageManager pm = getPackageManager();
                    for (Object key : whitelist.keySet()) {
                        String pkg = (String) key;
                        AppItem item = new AppItem();
                        item.pkg = pkg;
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                            item.name = ai.loadLabel(pm).toString();
                            item.icon = ai.loadIcon(pm);
                        } catch (Exception e) {
                            item.name = pkg;
                            item.icon = getDrawable(android.R.drawable.sym_def_app_icon);
                        }
                        mAllData.add(item);
                    }
                }
            }
        } catch (RemoteException e) { e.printStackTrace(); }
        filterList(mEtSearch.getText().toString());
    }

    private void filterList(String query) {
        mDisplayData.clear();
        if (query == null || query.isEmpty()) {
            mDisplayData.addAll(mAllData);
        } else {
            String lower = query.toLowerCase();
            for (AppItem item : mAllData) {
                if (item.name.toLowerCase().contains(lower) || item.pkg.toLowerCase().contains(lower)) {
                    mDisplayData.add(item);
                }
            }
        }
        mAdapter.notifyDataSetChanged();
        
        mLayoutEmpty.setVisibility(mDisplayData.isEmpty() ? View.VISIBLE : View.GONE);
        mListView.setVisibility(mDisplayData.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ================== Adapter ==================
    
    class AppItem {
        String name;
        String pkg;
        Drawable icon;
    }

    class WhitelistAdapter extends BaseAdapter {
        @Override public int getCount() { return mDisplayData.size(); }
        @Override public Object getItem(int position) { return mDisplayData.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                // 【核心修改】加载新的 item_whitelist 布局
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_whitelist, parent, false);
            }

            AppItem item = mDisplayData.get(position);

            ImageView ivIcon = convertView.findViewById(R.id.imgIcon);
            TextView tvName = convertView.findViewById(R.id.tvName);
            TextView tvPkg = convertView.findViewById(R.id.tvPkg);

            ivIcon.setImageDrawable(item.icon);
            tvName.setText(item.name);
            tvPkg.setText(item.pkg);

            return convertView;
        }
    }
}
