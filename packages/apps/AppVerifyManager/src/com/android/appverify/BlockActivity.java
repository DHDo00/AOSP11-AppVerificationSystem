package com.android.appverify;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class BlockActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block);
        
        Button btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 结束当前页面，回到桌面
                finishAffinity();
            }
        });
    }
    
    @Override
    public void onBackPressed() {
        // 拦截返回键，强制退出
        finishAffinity();
    }
}
