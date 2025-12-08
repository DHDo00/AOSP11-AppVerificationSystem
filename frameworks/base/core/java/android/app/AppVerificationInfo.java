package android.app;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Application verification information containing the five-tuple:
 * (packageName, appName, developerKeyHash, versionCode, packageHash)
 * 
 * @hide
 */
public class AppVerificationInfo implements Parcelable {
    public String packageName;
    public String appName;
    public String developerKeyHash;
    public int versionCode;
    public String versionName;
    public String packageHash;
    public long apkSize;
    public long installTime;
    public String installerPackage;
    
    public AppVerificationInfo() {}
    
    public AppVerificationInfo(String packageName, String appName,
                              String developerKeyHash, int versionCode,
                              String packageHash) {
        this.packageName = packageName;
        this.appName = appName;
        this.developerKeyHash = developerKeyHash;
        this.versionCode = versionCode;
        this.packageHash = packageHash;
        this.installTime = System.currentTimeMillis();
    }
    
    protected AppVerificationInfo(Parcel in) {
        packageName = in.readString();
        appName = in.readString();
        developerKeyHash = in.readString();
        versionCode = in.readInt();
        versionName = in.readString();
        packageHash = in.readString();
        apkSize = in.readLong();
        installTime = in.readLong();
        installerPackage = in.readString();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeString(appName);
        dest.writeString(developerKeyHash);
        dest.writeInt(versionCode);
        dest.writeString(versionName);
        dest.writeString(packageHash);
        dest.writeLong(apkSize);
        dest.writeLong(installTime);
        dest.writeString(installerPackage);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<AppVerificationInfo> CREATOR = 
        new Creator<AppVerificationInfo>() {
            @Override
            public AppVerificationInfo createFromParcel(Parcel in) {
                return new AppVerificationInfo(in);
            }
            
            @Override
            public AppVerificationInfo[] newArray(int size) {
                return new AppVerificationInfo[size];
            }
        };
    
    @Override
    public String toString() {
        return "AppVerificationInfo{" +
                "packageName='" + packageName + '\'' +
                ", appName='" + appName + '\'' +
                ", versionCode=" + versionCode +
                ", installTime=" + installTime +
                '}';
    }
}
