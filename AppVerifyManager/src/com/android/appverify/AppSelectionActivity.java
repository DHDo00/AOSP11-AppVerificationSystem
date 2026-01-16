package com.android.appverify;

import android.app.Activity;
import android.app.IAppVerificationManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppSelectionActivity extends Activity {

    private IAppVerificationManager mService;
    private ListView mListView;
    private SelectAdapter mAdapter;
    private List<SelectItem> mItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 复用 review 的布局，因为结构类似
        setContentView(R.layout.activity_review);

        // 1. 获取服务
        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        // 2. 初始化控件
        Button btnProcess = findViewById(R.id.btnProcess);
        btnProcess.setText("确认添加"); // 复用按钮，改个字
        Button btnSelectAll = findViewById(R.id.btnSelectAll);
        
        // 修改标题 (Hack方式: 如果你的 XML 里加了 ID 这里可以用 ID，否则用 tag 或遍历)
        TextView tvTitle = findViewById(R.id.tvPageTitle);
        if (tvTitle != null) tvTitle.setText(R.string.btn_scan_add);

        mListView = findViewById(R.id.listReview);
        mAdapter = new SelectAdapter();
        mListView.setAdapter(mAdapter);

        // 3. 加载数据
        loadInstalledApps();

        // 4. 事件监听
        btnSelectAll.setOnClickListener(v -> {
            boolean select = !isAllSelected();
            for (SelectItem i : mItems) i.isChecked = select;
            mAdapter.notifyDataSetChanged();
        });

        btnProcess.setOnClickListener(v -> addSelectedApps());
    }

    private void loadInstalledApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            Set<String> whitelist = new HashSet<>();

            // [修复点 1] 适配新接口: getWhitelist() 返回 Map
            try {
                if (mService != null) {
                    Map map = mService.getWhitelist();
                    if (map != null) {
                        for (Object key : map.keySet()) {
                            whitelist.add((String) key);
                        }
                    }
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            List<SelectItem> temp = new ArrayList<>();
            for (ApplicationInfo info : installed) {
                // 过滤掉系统应用和自己在白名单里的应用
                boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isUpdatedSystem = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                
                // 只显示：非系统应用 && 不在白名单里 && 不是自己
                if (!isSystem && !isUpdatedSystem && !whitelist.contains(info.packageName) 
                        && !"com.android.appverify".equals(info.packageName)) {
                    SelectItem item = new SelectItem();
                    item.info = info;
                    item.label = info.loadLabel(pm).toString();
                    temp.add(item);
                }
            }
            
            // 按名字排序
            Collections.sort(temp, (a, b) -> a.label.compareTo(b.label));

            runOnUiThread(() -> {
                mItems = temp;
                mAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void addSelectedApps() {
        int count = 0;
        for (SelectItem item : mItems) {
            if (item.isChecked) {
                try {
                    if (mService != null) {
                        // [修复点 2] 适配新接口: addAppToWhitelist
                        mService.addAppToWhitelist(item.info.packageName);
                        count++;
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
        Toast.makeText(this, "已添加 " + count + " 个应用", Toast.LENGTH_SHORT).show();
        finish(); // 完成后关闭页面
    }

    private boolean isAllSelected() {
        for (SelectItem i : mItems) if (!i.isChecked) return false;
        return !mItems.isEmpty();
    }

    class SelectItem {
        ApplicationInfo info;
        String label;
        boolean isChecked;
    }

    class SelectAdapter extends BaseAdapter {
        @Override
        public int getCount() { return mItems.size(); }
        @Override
        public Object getItem(int i) { return mItems.get(i); }
        @Override
        public long getItemId(int i) { return i; }
        @Override
        public View getView(int i, View v, ViewGroup p) {
            if (v == null) v = LayoutInflater.from(AppSelectionActivity.this)
                    .inflate(R.layout.item_review, p, false);
            
            SelectItem item = mItems.get(i);
            TextView tvName = v.findViewById(R.id.tvPkg);
            TextView tvReason = v.findViewById(R.id.tvReason); // 复用布局，这里显示包名
            CheckBox cb = v.findViewById(R.id.cbSelect);

            tvName.setText(item.label);
            tvReason.setText(item.info.packageName);
            cb.setChecked(item.isChecked);
            
            // 点击整行触发勾选
            v.setOnClickListener(view -> {
                item.isChecked = !item.isChecked;
                notifyDataSetChanged();
            });
            cb.setOnClickListener(view -> {
                item.isChecked = !item.isChecked;
                notifyDataSetChanged();
            });

            return v;
        }
    }
}
