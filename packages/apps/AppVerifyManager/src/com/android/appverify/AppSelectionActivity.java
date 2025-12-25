package com.android.appverify;

import android.app.Activity;
import android.app.IAppVerificationManager;
import android.app.ProgressDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppSelectionActivity extends Activity {

    private ListView mListView;
    private SelectAdapter mAdapter;
    private List<SelectItem> mItems = new ArrayList<>();
    private IAppVerificationManager mService;

    static class SelectItem {
        ApplicationInfo info;
        String label;
        boolean isChecked;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 复用 review 的布局
        setContentView(R.layout.activity_review);

        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        // 1. 初始化视图控件
        Button btnProcess = findViewById(R.id.btnProcess);
        Button btnSelectAll = findViewById(R.id.btnSelectAll);
        ListView mListView = findViewById(R.id.listReview);
        TextView tvTitle = findViewById(R.id.tvPageTitle); // 获取我们在XML里新命名的ID

        // 2. 修改界面文案 (适配 "添加应用" 的场景)
        // 标题设为 "添加应用" (复用 strings.xml 里的资源，保持统一)
        if (tvTitle != null) {
            tvTitle.setText(R.string.btn_scan_add); 
        }
        
        // 按钮设为 "确认添加"
        btnProcess.setText("确认添加");

        // 3. 设置 Adapter
        mAdapter = new SelectAdapter();
        mListView.setAdapter(mAdapter);

        // 4. 加载数据
        loadInstalledApps();

        // 5. 设置监听器
        btnSelectAll.setOnClickListener(v -> {
            boolean select = !isAllSelected();
            for (SelectItem i : mItems) i.isChecked = select;
            mAdapter.notifyDataSetChanged();
        });

        btnProcess.setOnClickListener(v -> addSelectedApps());
    }
    private boolean isAllSelected() {
        for (SelectItem i : mItems) if (!i.isChecked) return false;
        return !mItems.isEmpty();
    }

    private void loadInstalledApps() {
        final ProgressDialog pd = ProgressDialog.show(this, "扫描中", "正在筛选第三方应用...", true);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            
            Set<String> whitelist = new HashSet<>();
            try {
                if (mService != null) whitelist.addAll(mService.getWhitelistedApps());
            } catch (RemoteException e) {}

            List<SelectItem> temp = new ArrayList<>();
            for (ApplicationInfo app : installed) {
                // 1. 严格过滤系统应用
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || 
                    (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    continue;
                }
                // 2. 过滤已添加的
                if (whitelist.contains(app.packageName)) continue;
                // 3. 过滤自己
                if (getPackageName().equals(app.packageName)) continue;

                SelectItem item = new SelectItem();
                item.info = app;
                item.label = pm.getApplicationLabel(app).toString();
                item.isChecked = false;
                temp.add(item);
            }

            runOnUiThread(() -> {
                pd.dismiss();
                mItems = temp;
                mAdapter.notifyDataSetChanged();
                if (mItems.isEmpty()) Toast.makeText(this, "没有可添加的应用", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void addSelectedApps() {
        if (mService == null) return;
        new Thread(() -> {
            int count = 0;
            for (SelectItem item : mItems) {
                if (item.isChecked) {
                    try {
                        mService.addToWhitelist(item.info.packageName);
                        count++;
                    } catch (RemoteException e) {}
                }
            }
            final int finalCount = count;
            runOnUiThread(() -> {
                Toast.makeText(this, "已添加 " + finalCount + " 个应用", Toast.LENGTH_SHORT).show();
                finish();
            });
        }).start();
    }

    private class SelectAdapter extends BaseAdapter {
        @Override
        public int getCount() { return mItems.size(); }
        @Override
        public Object getItem(int position) { return mItems.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // 复用 item_event.xml (带CheckBox的布局)
            if (convertView == null) {
                convertView = LayoutInflater.from(AppSelectionActivity.this)
                        .inflate(R.layout.item_event, parent, false);
            }
            SelectItem item = mItems.get(position);
            PackageManager pm = getPackageManager();

            CheckBox cb = convertView.findViewById(R.id.cbSelect);
            ImageView icon = convertView.findViewById(R.id.imgIcon);
            TextView title = convertView.findViewById(R.id.tvTitle);
            TextView sub = convertView.findViewById(R.id.tvSubtitle);

            cb.setChecked(item.isChecked);
            icon.setImageDrawable(item.info.loadIcon(pm));
            title.setText(item.label);
            sub.setText(item.info.packageName);

            convertView.setOnClickListener(v -> {
                item.isChecked = !item.isChecked;
                cb.setChecked(item.isChecked);
            });
            cb.setOnClickListener(v -> item.isChecked = cb.isChecked());

            return convertView;
        }
    }
}
