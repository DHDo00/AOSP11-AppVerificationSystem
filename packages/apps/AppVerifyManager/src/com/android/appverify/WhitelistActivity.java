package com.android.appverify;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.IAppVerificationManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class WhitelistActivity extends Activity {

    private IAppVerificationManager mService;
    private ListView mListView;
    private ListAdapter mAdapter;
    private List<AppItem> mAllItems = new ArrayList<>();
    private List<AppItem> mDisplayItems = new ArrayList<>();

    static class AppItem {
        String pkg;
        String name;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist);

        mService = IAppVerificationManager.Stub.asInterface(
                ServiceManager.getService("app_verification"));

        mListView = findViewById(R.id.listWhitelist);
        SearchView searchView = findViewById(R.id.searchView);

        mAdapter = new ListAdapter();
        mListView.setAdapter(mAdapter);

        // 长按删除
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            AppItem item = mDisplayItems.get(position);
            showDeleteDialog(item);
            return true;
        });

        // 点击显示 Hash
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            AppItem item = mDisplayItems.get(position);
            showHashDialog(item);
        });

        // 搜索框逻辑
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) { return false; }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterList(newText);
                return true;
            }
        });

        loadData();
    }

    private void loadData() {
        new Thread(() -> {
            try {
                if (mService == null) return;
                List<String> pkgs = mService.getWhitelistedApps();
                PackageManager pm = getPackageManager();
                List<AppItem> temp = new ArrayList<>();

                for (String pkg : pkgs) {
                    AppItem item = new AppItem();
                    item.pkg = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        
                        // 【核心改动】过滤系统应用：只显示用户安装的第三方应用
                        boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        boolean isUpdatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                        
                        // 如果是系统应用，自动隐藏（因为服务端已自动放行，无需用户管理）
                        if (isSystem || isUpdatedSystem) {
                            continue;
                        }

                        item.name = pm.getApplicationLabel(ai).toString();
                        item.icon = pm.getApplicationIcon(ai);
                    } catch (PackageManager.NameNotFoundException e) {
                        item.name = pkg + " (未安装)";
                        item.icon = getDrawable(android.R.drawable.sym_def_app_icon);
                    }
                    temp.add(item);
                }

                runOnUiThread(() -> {
                    mAllItems = temp;
                    filterList("");
                });
            } catch (RemoteException e) { e.printStackTrace(); }
        }).start();
    }

    private void filterList(String query) {
        mDisplayItems.clear();
        if (query == null || query.isEmpty()) {
            mDisplayItems.addAll(mAllItems);
        } else {
            String q = query.toLowerCase();
            for (AppItem item : mAllItems) {
                if (item.name.toLowerCase().contains(q) || item.pkg.toLowerCase().contains(q)) {
                    mDisplayItems.add(item);
                }
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private void showHashDialog(AppItem item) {
        String hash = HashUtils.getSignatureHash(this, item.pkg);
        
        // 构造显示内容
        StringBuilder msg = new StringBuilder();
        msg.append("App: ").append(item.name).append("\n");
        msg.append("Pkg: ").append(item.pkg).append("\n\n");
        // [修改点] 使用 strings.xml 里的 "应用校验值"
        msg.append(getString(R.string.label_hash_value)).append(":\n"); 
        msg.append(hash);

        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_hash_title) // 标题已改为 "完整性校验"
            .setMessage(msg.toString())
            .setPositiveButton(R.string.btn_copy, (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setText(hash);
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.btn_close, null)
            .show();
    }
    private void showDeleteDialog(AppItem item) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_msg, item.name))
            .setPositiveButton(R.string.btn_confirm, (d, w) -> {
                try {
                    mService.removeFromWhitelist(item.pkg);
                    loadData(); // 刷新
                    Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
                } catch (RemoteException e) { e.printStackTrace(); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private class ListAdapter extends BaseAdapter {
        @Override
        public int getCount() { return mDisplayItems.size(); }
        @Override
        public Object getItem(int position) { return mDisplayItems.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // 复用之前的 item_whitelist.xml
            if (convertView == null) {
                convertView = LayoutInflater.from(WhitelistActivity.this)
                        .inflate(R.layout.item_whitelist, parent, false);
            }
            AppItem item = mDisplayItems.get(position);
            ((ImageView) convertView.findViewById(R.id.imgIcon)).setImageDrawable(item.icon);
            ((TextView) convertView.findViewById(R.id.tvAppName)).setText(item.name);
            ((TextView) convertView.findViewById(R.id.tvPackageName)).setText(item.pkg);
            return convertView;
        }
    }
}
