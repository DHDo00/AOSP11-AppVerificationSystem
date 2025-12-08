package android.app;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 应用验证信息
 * @hide
 */
public class AppVerificationInfo implements Parcelable {
    private String packageName;
    private boolean isWhitelisted;
    private boolean isSignatureValid;
    private String signatureHash;
    private long lastVerificationTime;
    
    // 构造函数：必须与 Service 中的调用完全匹配
    public AppVerificationInfo(String packageName, boolean isWhitelisted, 
                               boolean isSignatureValid, String signatureHash) {
        this.packageName = packageName;
        this.isWhitelisted = isWhitelisted;
        this.isSignatureValid = isSignatureValid;
        this.signatureHash = signatureHash;
        this.lastVerificationTime = System.currentTimeMillis();
    }
    
    // Getters
    public String getPackageName() { return packageName; }
    public boolean isWhitelisted() { return isWhitelisted; }
    public boolean isSignatureValid() { return isSignatureValid; }
    public String getSignatureHash() { return signatureHash; }
    public long getLastVerificationTime() { return lastVerificationTime; }
    
    // Parcelable implementation
    protected AppVerificationInfo(Parcel in) {
        packageName = in.readString();
        isWhitelisted = in.readByte() != 0;
        isSignatureValid = in.readByte() != 0;
        signatureHash = in.readString();
        lastVerificationTime = in.readLong();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeByte((byte) (isWhitelisted ? 1 : 0));
        dest.writeByte((byte) (isSignatureValid ? 1 : 0));
        dest.writeString(signatureHash);
        dest.writeLong(lastVerificationTime);
    }
    
    @Override
    public int describeContents() { return 0; }
    
    public static final Creator<AppVerificationInfo> CREATOR = new Creator<AppVerificationInfo>() {
        @Override
        public AppVerificationInfo createFromParcel(Parcel in) {
            return new AppVerificationInfo(in);
        }
        
        @Override
        public AppVerificationInfo[] newArray(int size) {
            return new AppVerificationInfo[size];
        }
    };
}
