package com.qimu.guide;

import android.app.Application;
import android.content.Context;

import com.moyoung.glasses.CRPBleClient;
import com.moyoung.glasses.util.BleLog;

public class QimuApplication extends Application {

    private static Context appContext;
    private CRPBleClient mBleClient;

    public static CRPBleClient getBleClient() {
        QimuApplication app = (QimuApplication) appContext;
        return app.mBleClient;
    }

    public static Context getAppContext() {
        return appContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;
        BleLog.isPrint = true;
        mBleClient = CRPBleClient.create(this);
        com.qimu.guide.net.ApiConfig.init(this);  // 初始化后端地址(从SharedPreferences读上次设置)
    }
}
