package com.android.appverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.IAppVerificationManager;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String TAG = "AppVerifyManager";
    private IAppVerificationManager mService;
    
    // UI 控件
    private ListView mListView;
    private ArrayAdapter<String> mAdapter;
    private final java.util.List<String> mWhitelistData = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        if (mService == null) {
            Log.e(TAG, "Failed to connect to app_verification service");
            Toast.makeText(this, "Fatal: Service not found!", Toast.LENGTH_LONG).show();
            return;
        }

        initViews();
        refreshWhitelist();
    }

    private void initViews() {
        mListView = findViewById(R.id.listViewApps);
        RadioGroup rgMode = findViewById(R.id.radioGroupMode);
        Button btnImportAll = findViewById(R.id.btnImportAll);
        Button btnCleanInvalid = findViewById(R.id.btnCleanInvalid);

        // 初始化列表
        mAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, mWhitelistData);
        mListView.setAdapter(mAdapter);

        // 长按删除功能
        mListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                String pkg = mWhitelistData.get(position);
                showDeleteDialog(pkg);
                return true;
            }
        });

        // 批量导入
        btnImportAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performImportAll();
            }
        });

        // 清理无效条目
        btnCleanInvalid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performCleanInvalid();
            }
        });

        // 模式切换
        rgMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int mode = 1; 
                if (checkedId == R.id.rbDisabled) {
                    mode = 0; // 禁用模式
                } else if (checkedId == R.id.rbWhitelist) {
                    mode = 1; // 启用模式 (原 Whitelist)
                }
                setMode(mode);
            }
        });
        
        // 默认选中启用模式
        ((RadioButton)findViewById(R.id.rbWhitelist)).setChecked(true);
    }

    // ==========================================
    // 核心业务逻辑
    // ==========================================

    private void performImportAll() {
        final ProgressDialog dialog = ProgressDialog.show(this, "请稍候", 
                getString(R.string.msg_importing), true);
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> installedApps = pm.getInstalledApplications(
				PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.GET_META_DATA);
 
		Log.d(TAG, "DEBUG: Scanned total apps: " + installedApps.size());

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
                } catch (RemoteException e) {
                    Log.e(TAG, "Error during bulk import", e);
                }
                
                final int finalCount = count;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dialog.dismiss();
                        String msg = getString(R.string.toast_import_success, finalCount);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                        refreshWhitelist();
                    }
                });
            }
        }).start();
    }

    private void performCleanInvalid() {
        final ProgressDialog dialog = ProgressDialog.show(this, "请稍候", 
                getString(R.string.msg_cleaning), true);

        new Thread(new Runnable() {
            @Override
            public void run() {
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
                            Log.i(TAG, "Cleaned invalid package: " + pkgName);
                        }
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Error during clean up", e);
                }

                final int finalCount = count;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dialog.dismiss();
                        String msg = getString(R.string.toast_clean_success, finalCount);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                        refreshWhitelist();
                    }
                });
            }
        }).start();
    }

    private void refreshWhitelist() {
        if (mService == null) return;
        try {
            List<String> apps = mService.getWhitelistedApps();
            mWhitelistData.clear();
            if (apps != null) {
                mWhitelistData.addAll(apps);
            }
            mAdapter.notifyDataSetChanged();
        } catch (RemoteException e) {
            Log.e(TAG, "Error refreshing whitelist", e);
        }
    }
    
    private void showDeleteDialog(final String pkgName) {
        String title = getString(R.string.dialog_delete_title);
        String msg = getString(R.string.dialog_delete_msg, pkgName);
        String btnConfirm = getString(R.string.btn_confirm);
        String btnCancel = getString(R.string.btn_cancel);

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(btnConfirm, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    removePackage(pkgName);
                }
            })
            .setNegativeButton(btnCancel, null)
            .show();
    }

    private void removePackage(String pkgName) {
        try {
            boolean success = mService.removeFromWhitelist(pkgName);
            if (success) {
                String msg = getString(R.string.toast_removed) + pkgName;
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                refreshWhitelist();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error removing package", e);
        }
    }
    
    private void setMode(int mode) {
        try {
            mService.setVerificationMode(mode);
            Toast.makeText(this, R.string.toast_mode_updated, Toast.LENGTH_SHORT).show();
        } catch (RemoteException e) {
            Log.e(TAG, "Error setting mode", e);
        }
    }
}
