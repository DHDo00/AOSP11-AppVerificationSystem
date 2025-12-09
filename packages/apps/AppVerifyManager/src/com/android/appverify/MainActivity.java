package com.android.appverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.IAppVerificationManager;
import android.content.DialogInterface;
import android.content.Intent;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String TAG = "AppVerifyManager";
    private IAppVerificationManager mService;
    
    // UI 控件
    private ListView mListView;
    private AppAdapter mAdapter;
    private final List<AppItem> mAppItems = new ArrayList<>();

    private static class AppItem {
        String packageName;
        String appName;
        Drawable icon;

        public AppItem(String packageName, String appName, Drawable icon) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        if (mService == null) {
            Toast.makeText(this, "Fatal: Service not found!", Toast.LENGTH_LONG).show();
            return;
        }

        initViews();
        // onCreate 时不需要立即刷新，因为 onResume 会马上被调用
    }

    /**
     * [新增] 生命周期方法
     * 每次界面回到前台（比如从审查页返回，或者从后台切回）都会自动刷新
     * 完美解决"必须重启才能看到变化"的问题
     */
    @Override
    protected void onResume() {
        super.onResume();
        refreshWhitelist();
    }

    private void initViews() {
        mListView = findViewById(R.id.listViewApps);
        RadioGroup rgMode = findViewById(R.id.radioGroupMode);
        Button btnImportAll = findViewById(R.id.btnImportAll);
        Button btnCleanInvalid = findViewById(R.id.btnCleanInvalid);
        Button btnReview = findViewById(R.id.btnReview);
        Button btnRefresh = findViewById(R.id.btnRefresh); // [新增]

        mAdapter = new AppAdapter(this, mAppItems);
        mListView.setAdapter(mAdapter);

        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            AppItem item = mAppItems.get(position);
            showDeleteDialog(item);
            return true;
        });

        btnImportAll.setOnClickListener(v -> performImportAll());
        btnCleanInvalid.setOnClickListener(v -> performCleanInvalid());
        
        btnReview.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, ReviewActivity.class));
        });

        // [新增] 刷新按钮逻辑
        btnRefresh.setOnClickListener(v -> {
            refreshWhitelist();
            Toast.makeText(MainActivity.this, "列表已刷新", Toast.LENGTH_SHORT).show();
        });

        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = 0; // 默认为禁用
            if (checkedId == R.id.rbDisabled) mode = 0;
            else if (checkedId == R.id.rbWhitelist) mode = 1;
            setMode(mode);
        });
        
        // [修改] 默认选中 "禁用模式 (Disabled)"
        // 注意：这只是 UI 默认值，如果想让系统开机默认禁用，需修改 Framework Service 代码
        ((RadioButton)findViewById(R.id.rbDisabled)).setChecked(true);
    }

    // ==========================================
    // 核心业务逻辑
    // ==========================================

    private void refreshWhitelist() {
        if (mService == null) return;
        
        new Thread(() -> {
            try {
                List<String> pkgs = mService.getWhitelistedApps();
                PackageManager pm = getPackageManager();
                final List<AppItem> tempItems = new ArrayList<>();
                
                for (String pkg : pkgs) {
                    String name = pkg;
                    Drawable icon = getDrawable(android.R.drawable.sym_def_app_icon);
                    try {
                        ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                        name = pm.getApplicationLabel(info).toString();
                        icon = pm.getApplicationIcon(info);
                    } catch (PackageManager.NameNotFoundException e) {
                        name = pkg + " (未安装)";
                    }
                    tempItems.add(new AppItem(pkg, name, icon));
                }
                
                Collections.sort(tempItems, (o1, o2) -> {
                    Collator collator = Collator.getInstance(Locale.CHINA);
                    return collator.compare(o1.appName, o2.appName);
                });

                runOnUiThread(() -> {
                    mAppItems.clear();
                    mAppItems.addAll(tempItems);
                    mAdapter.notifyDataSetChanged();
                });
                
            } catch (RemoteException e) {
                Log.e(TAG, "Error refreshing", e);
            }
        }).start();
    }

    private void performImportAll() {
        final ProgressDialog dialog = ProgressDialog.show(this, "请稍候", getString(R.string.msg_importing), true);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES);
            int count = 0;
            try {
                List<String> currentWhitelist = mService.getWhitelistedApps();
                Set<String> whitelistSet = new HashSet<>(currentWhitelist);
                for (ApplicationInfo app : installedApps) {
                    if (!whitelistSet.contains(app.packageName)) {
                        mService.addToWhitelist(app.packageName);
                        count++;
                    }
                }
            } catch (RemoteException e) { Log.e(TAG, "Error", e); }
            
            final int finalCount = count;
            runOnUiThread(() -> {
                dialog.dismiss();
                Toast.makeText(MainActivity.this, getString(R.string.toast_import_success, finalCount), Toast.LENGTH_SHORT).show();
                refreshWhitelist();
            });
        }).start();
    }

    private void performCleanInvalid() {
        final ProgressDialog dialog = ProgressDialog.show(this, "请稍候", getString(R.string.msg_cleaning), true);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            int count = 0;
            try {
                List<String> whitelist = mService.getWhitelistedApps();
                for (String pkgName : whitelist) {
                    try {
                        pm.getPackageInfo(pkgName, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        mService.removeFromWhitelist(pkgName);
                        count++;
                    }
                }
            } catch (RemoteException e) { Log.e(TAG, "Error", e); }

            final int finalCount = count;
            runOnUiThread(() -> {
                dialog.dismiss();
                Toast.makeText(MainActivity.this, getString(R.string.toast_clean_success, finalCount), Toast.LENGTH_SHORT).show();
                refreshWhitelist();
            });
        }).start();
    }
    
    private void showDeleteDialog(final AppItem item) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_msg, item.appName))
            .setPositiveButton(R.string.btn_confirm, (d, w) -> removePackage(item.packageName))
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void removePackage(String pkgName) {
        try {
            boolean success = mService.removeFromWhitelist(pkgName);
            if (success) {
                Toast.makeText(this, getString(R.string.toast_removed) + pkgName, Toast.LENGTH_SHORT).show();
                refreshWhitelist();
            }
        } catch (RemoteException e) { Log.e(TAG, "Error", e); }
    }
    
    private void setMode(int mode) {
        try {
            mService.setVerificationMode(mode);
            Toast.makeText(this, R.string.toast_mode_updated, Toast.LENGTH_SHORT).show();
        } catch (RemoteException e) { Log.e(TAG, "Error", e); }
    }

    private class AppAdapter extends ArrayAdapter<AppItem> {
        public AppAdapter(Activity context, List<AppItem> items) {
            super(context, R.layout.item_whitelist, items);
        }
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_whitelist, parent, false);
            }
            AppItem item = getItem(position);
            ImageView img = convertView.findViewById(R.id.imgIcon);
            TextView tvName = convertView.findViewById(R.id.tvAppName);
            TextView tvPkg = convertView.findViewById(R.id.tvPackageName);
            if (item != null) {
                img.setImageDrawable(item.icon);
                tvName.setText(item.appName);
                tvPkg.setText(item.packageName);
            }
            return convertView;
        }
    }
}
