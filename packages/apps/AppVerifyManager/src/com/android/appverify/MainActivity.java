package com.android.appverify;

import android.app.Activity;
import android.app.IAppVerificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.Toast;

public class MainActivity extends Activity {

    private IAppVerificationManager mService;
    private Switch mSwitchProtection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        if (mService == null) {
            Toast.makeText(this, "严重错误：无法连接到系统服务！", Toast.LENGTH_LONG).show();
        }

        initViews();
    }

    private void initViews() {
        mSwitchProtection = findViewById(R.id.switchProtection);
        Button btnScanAdd = findViewById(R.id.btnScanAdd);
        Button btnManageWhitelist = findViewById(R.id.btnManageWhitelist);
        Button btnReview = findViewById(R.id.btnReview);

        // 1. 开关监听
        mSwitchProtection.setOnClickListener(v -> {
            boolean newState = mSwitchProtection.isChecked();
            setMode(newState ? 1 : 0);
        });

        // 2. 按钮跳转
        btnScanAdd.setOnClickListener(v -> startActivity(new Intent(this, AppSelectionActivity.class)));
        btnManageWhitelist.setOnClickListener(v -> startActivity(new Intent(this, WhitelistActivity.class)));
        btnReview.setOnClickListener(v -> startActivity(new Intent(this, ReviewActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSwitchState();
    }

    private void updateSwitchState() {
        if (mService == null) return;
        try {
            int mode = mService.getVerificationMode();
            // mode 1 = 开启 (Whitelist), mode 0 = 禁用
            mSwitchProtection.setChecked(mode == 1);
            mSwitchProtection.setText(mode == 1 ? R.string.mode_enabled : R.string.mode_disabled);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void setMode(int mode) {
        if (mService == null) return;
        try {
            mService.setVerificationMode(mode);
            updateSwitchState(); // 更新文字
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
