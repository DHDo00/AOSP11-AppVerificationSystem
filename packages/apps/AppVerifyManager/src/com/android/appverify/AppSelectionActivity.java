package com.android.appverify;

import android.app.Activity;
import android.app.IAppVerificationManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AppSelectionActivity extends Activity {

    private IAppVerificationManager mService;
    private ListView mListView;
    private EditText mEtSearch;
    private View mBtnSelectAll;
    private ProgressBar mLoadingProgress;
    private AppAdapter mAdapter;
    
    private List<AppInfo> mAllApps = new ArrayList<>();
    private List<AppInfo> mData = new ArrayList<>();
    private boolean mIsAllSelected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_selection);

        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        initViews();
        loadInstalledApps();
    }

    private void initViews() {
        // 返回按钮
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // 列表
        mListView = findViewById(R.id.listApps);
        mLoadingProgress = findViewById(R.id.loadingProgress);
        mAdapter = new AppAdapter();
        mListView.setAdapter(mAdapter);

        // 点击 Item 勾选
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo item = mData.get(position);
            item.isChecked = !item.isChecked;
            mAdapter.notifyDataSetChanged();
        });

        // 确认按钮
        View btnConfirm = findViewById(R.id.btnConfirm);
        if (btnConfirm != null) btnConfirm.setOnClickListener(v -> confirmSelection());

        // 搜索框
        mEtSearch = findViewById(R.id.etSearch);
        mEtSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { filterApps(s.toString()); }
        });

        // 全选
        mBtnSelectAll = findViewById(R.id.btnSelectAll);
        if (mBtnSelectAll != null) mBtnSelectAll.setOnClickListener(v -> toggleSelectAll());
    }

    private void loadInstalledApps() {
        if (mLoadingProgress != null) mLoadingProgress.setVisibility(View.VISIBLE);
        
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            
            Map currentWhitelist = null;
            try {
                if (mService != null) currentWhitelist = mService.getWhitelist();
            } catch (RemoteException e) { e.printStackTrace(); }

            List<AppInfo> tempList = new ArrayList<>();
            for (ApplicationInfo info : installed) {
                // 排除系统应用和自己
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (getPackageName().equals(info.packageName)) continue;
                if (currentWhitelist != null && currentWhitelist.containsKey(info.packageName)) continue;

                AppInfo app = new AppInfo();
                app.name = info.loadLabel(pm).toString();
                app.pkg = info.packageName;
                app.icon = info.loadIcon(pm);
                app.isChecked = false;
                tempList.add(app);
            }

            Collections.sort(tempList, (o1, o2) -> o1.name.compareToIgnoreCase(o2.name));

            new Handler(Looper.getMainLooper()).post(() -> {
                mAllApps.clear();
                mAllApps.addAll(tempList);
                mData.clear();
                mData.addAll(tempList);
                mAdapter.notifyDataSetChanged();
                if (mLoadingProgress != null) mLoadingProgress.setVisibility(View.GONE);
            });
        }).start();
    }

    private void filterApps(String query) {
        mData.clear();
        if (query == null || query.isEmpty()) {
            mData.addAll(mAllApps);
        } else {
            String lowerQuery = query.toLowerCase();
            for (AppInfo app : mAllApps) {
                if (app.name.toLowerCase().contains(lowerQuery) || app.pkg.toLowerCase().contains(lowerQuery)) {
                    mData.add(app);
                }
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private void toggleSelectAll() {
        mIsAllSelected = !mIsAllSelected;
        for (AppInfo app : mData) app.isChecked = mIsAllSelected;
        mAdapter.notifyDataSetChanged();
    }

    private void confirmSelection() {
        int count = 0;
        try {
            if (mService != null) {
                for (AppInfo app : mAllApps) {
                    if (app.isChecked) {
                        mService.addAppToWhitelist(app.pkg);
                        count++;
                    }
                }
                Toast.makeText(this, "成功添加 " + count + " 个应用", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK); // 告诉主页刷新
                finish();
            }
        } catch (RemoteException e) {
            Toast.makeText(this, "添加失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    static class AppInfo {
        String name;
        String pkg;
        Drawable icon;
        boolean isChecked;
    }

    class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return mData.size(); }
        @Override public Object getItem(int position) { return mData.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(AppSelectionActivity.this)
                        .inflate(R.layout.item_app_selection, parent, false);
            }
            AppInfo item = mData.get(position);
            
            ((ImageView) convertView.findViewById(R.id.imgIcon)).setImageDrawable(item.icon);
            ((TextView) convertView.findViewById(R.id.tvName)).setText(item.name);
            ((TextView) convertView.findViewById(R.id.tvPkg)).setText(item.pkg);
            
            CheckBox cb = convertView.findViewById(R.id.cbSelect);
            cb.setVisibility(View.VISIBLE); // 必须显示
            cb.setChecked(item.isChecked);

            return convertView;
        }
    }
}
