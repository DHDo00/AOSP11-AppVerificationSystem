package com.android.appverify;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.security.MessageDigest;

public class HashUtils {
    public static String getSignatureHash(Context context, String packageName) {
        try {
            PackageInfo pkgInfo = context.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_SIGNATURES);
            
            if (pkgInfo.signatures == null || pkgInfo.signatures.length == 0) {
                return "无签名信息";
            }

            // 获取第一个签名
            byte[] signatureBytes = pkgInfo.signatures[0].toByteArray();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signatureBytes);
            
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "无法计算: " + e.getMessage();
        }
    }
}
