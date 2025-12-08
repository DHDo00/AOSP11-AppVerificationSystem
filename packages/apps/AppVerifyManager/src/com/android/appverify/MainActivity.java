package com.android.appverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.IAppVerificationManager;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

// 为了避免 AppCompat 主题冲突，这里建议使用 Activity
// 如果编译报错找不到 AppCompatActivity，修改 AndroidManifest.xml 的 theme 为 @android:style/Theme.DeviceDefault 即可
public class MainActivity extends Activity {

    private static final String TAG = "AppVerifyManager";
    private IAppVerificationManager mService;
    
    // UI 控件
    private EditText mEtPackage;
    private ListView mListView;
    private ArrayAdapter<String> mAdapter;
    private final java.util.List<String> mWhitelistData = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 核心步骤：获取 Framework 服务代理
        // "app_verification" 必须与 SystemServer.java 中注册的名字一致
        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        if (mService == null) {
            Log.e(TAG, "Failed to connect to app_verification service");
            Toast.makeText(this, "Fatal: Service not found!", Toast.LENGTH_LONG).show();
            // 此时服务未启动，无法继续
            return;
        }

        initViews();
        refreshWhitelist();
    }

    private void initViews() {
        mEtPackage = findViewById(R.id.etPackageName);
        mListView = findViewById(R.id.listViewApps);
        Button btnAdd = findViewById(R.id.btnAdd);
        RadioGroup rgMode = findViewById(R.id.radioGroupMode);

        // 初始化列表适配器
        mAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, mWhitelistData);
        mListView.setAdapter(mAdapter);

        // 设置长按删除事件
        mListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                String pkg = mWhitelistData.get(position);
                showDeleteDialog(pkg);
                return true;
            }
        });

        // 设置添加按钮事件
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = mEtPackage.getText().toString().trim();
                if (!pkg.isEmpty()) {
                    addPackage(pkg);
                }
            }
        });

        // 设置模式切换事件
        rgMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int mode = 1; // Default Whitelist
                if (checkedId == R.id.rbDisabled) mode = 0;
                else if (checkedId == R.id.rbWhitelist) mode = 1;
                else if (checkedId == R.id.rbSignature) mode = 2;
                
                setMode(mode);
            }
        });
        
        // 默认选中中间的白名单模式 (UI显示)
        ((RadioButton)findViewById(R.id.rbWhitelist)).setChecked(true);
    }

    private void refreshWhitelist() {
        if (mService == null) return;
        try {
            // 调用 AIDL 获取列表
            java.util.List<String> apps = mService.getWhitelistedApps();
            mWhitelistData.clear();
            if (apps != null) {
                mWhitelistData.addAll(apps);
            }
            // 刷新 UI 必须在主线程 (虽然这里的 AIDL 调用是同步的，但在 Activity 中直接调就是主线程)
            mAdapter.notifyDataSetChanged();
        } catch (RemoteException e) {
            Log.e(TAG, "Error refreshing whitelist", e);
        }
    }

    private void addPackage(String pkgName) {
        try {
            mService.addToWhitelist(pkgName);
            mEtPackage.setText(""); // 清空输入框
            Toast.makeText(this, "Added: " + pkgName, Toast.LENGTH_SHORT).show();
            refreshWhitelist(); // 刷新列表查看结果
        } catch (RemoteException e) {
            Log.e(TAG, "Error adding package", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showDeleteDialog(final String pkgName) {
        new AlertDialog.Builder(this)
            .setTitle("Confirm Delete")
            .setMessage("Remove " + pkgName + " from whitelist?")
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    removePackage(pkgName);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void removePackage(String pkgName) {
        try {
            // 调用 AIDL 移除
            boolean success = mService.removeFromWhitelist(pkgName);
            if (success) {
                Toast.makeText(this, "Removed: " + pkgName, Toast.LENGTH_SHORT).show();
                refreshWhitelist();
            } else {
                Toast.makeText(this, "Failed: Package not found", Toast.LENGTH_SHORT).show();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error removing package", e);
        }
    }
    
    private void setMode(int mode) {
        try {
            mService.setVerificationMode(mode);
            Toast.makeText(this, "Mode updated", Toast.LENGTH_SHORT).show();
        } catch (RemoteException e) {
            Log.e(TAG, "Error setting mode", e);
        }
    }
}
