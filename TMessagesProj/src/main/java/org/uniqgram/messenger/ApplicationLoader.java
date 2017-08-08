/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.uniqgram.messenger;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Base64;

import com.google.android.gms.ads.MobileAds;

import org.uniqgram.tgnet.ConnectionsManager;
import org.uniqgram.tgnet.SerializedData;
import org.uniqgram.tgnet.TLRPC;
import org.uniqgram.ui.Components.ForegroundDetector;

import java.io.File;
import java.io.RandomAccessFile;

public class ApplicationLoader extends Application {
    private static PendingIntent pendingIntent;

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile Handler applicationHandler;
    private static volatile boolean applicationInited = false;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static volatile long mainInterfacePausedStageQueueTime;

    private static void convertConfig() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("dataconfig", Context.MODE_PRIVATE);
        if (preferences.contains("currentDatacenterId")) {
            SerializedData buffer = new SerializedData(32 * 1024);
            buffer.writeInt32(2);
            buffer.writeBool(preferences.getInt("datacenterSetId", 0) != 0);
            buffer.writeBool(true);
            buffer.writeInt32(preferences.getInt("currentDatacenterId", 0));
            buffer.writeInt32(preferences.getInt("timeDifference", 0));
            buffer.writeInt32(preferences.getInt("lastDcUpdateTime", 0));
            buffer.writeInt64(preferences.getLong("pushSessionId", 0));
            buffer.writeBool(false);
            buffer.writeInt32(0);
            try {
                String datacentersString = preferences.getString("datacenters", null);
                if (datacentersString != null) {
                    byte[] datacentersBytes = Base64.decode(datacentersString, Base64.DEFAULT);
                    if (datacentersBytes != null) {
                        SerializedData data = new SerializedData(datacentersBytes);
                        buffer.writeInt32(data.readInt32(false));
                        buffer.writeBytes(datacentersBytes, 4, datacentersBytes.length - 4);
                        data.cleanup();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }

            try {
                File file = new File(getFilesDirFixed(), "tgnet.dat");
                RandomAccessFile fileOutputStream = new RandomAccessFile(file, "rws");
                byte[] bytes = buffer.toByteArray();
                fileOutputStream.writeInt(Integer.reverseBytes(bytes.length));
                fileOutputStream.write(bytes);
                fileOutputStream.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
            buffer.cleanup();
            preferences.edit().clear().commit();
        }
    }

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new File("/data/data/org.uniqgram.messenger/files");
    }

    public static void postInitApplication() {
        if (applicationInited) {
            return;
        }

        applicationInited = true;
        convertConfig();

        try {
            LocaleController.getInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager)ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            FileLog.e("screen state = " + isScreenOn);
        } catch (Exception e) {
            FileLog.e(e);
        }

        UserConfig.loadConfig();
        String deviceModel;
        String systemLangCode;
        String langCode;
        String appVersion;
        String systemVersion;
        String configPath = getFilesDirFixed().toString();

        try {
            systemLangCode = LocaleController.getSystemLocaleStringIso639();
            langCode = LocaleController.getLocaleStringIso639();
            deviceModel = Build.MANUFACTURER + Build.MODEL;
            PackageInfo pInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            appVersion = pInfo.versionName + " (" + pInfo.versionCode + ")";
            systemVersion = "SDK " + Build.VERSION.SDK_INT;
        } catch (Exception e) {
            systemLangCode = "en";
            langCode = "";
            deviceModel = "Android unknown";
            appVersion = "App version unknown";
            systemVersion = "SDK " + Build.VERSION.SDK_INT;
        }
        if (systemLangCode.trim().length() == 0) {
            langCode = "en";
        }
        if (deviceModel.trim().length() == 0) {
            deviceModel = "Android unknown";
        }
        if (appVersion.trim().length() == 0) {
            appVersion = "App version unknown";
        }
        if (systemVersion.trim().length() == 0) {
            systemVersion = "SDK Unknown";
        }
        // Telegram-FOSS: Unconditionally enable push connection
        boolean enablePushConnection = true;

        MessagesController.getInstance();
        ConnectionsManager.getInstance().init(BuildVars.BUILD_VERSION, TLRPC.LAYER, BuildVars.APP_ID, deviceModel, systemVersion, appVersion, langCode, systemLangCode, configPath, FileLog.getNetworkLogPath(), UserConfig.getClientUserId(), enablePushConnection);
        if (UserConfig.getCurrentUser() != null) {
            MessagesController.getInstance().putUser(UserConfig.getCurrentUser(), true);
            ConnectionsManager.getInstance().applyCountryPortNumber(UserConfig.getCurrentUser().phone);
            MessagesController.getInstance().getBlockedUsers(true);
            SendMessagesHelper.getInstance().checkUnsentMessages();
        }

        ApplicationLoader app = (ApplicationLoader)ApplicationLoader.applicationContext;

        String TestAdsId = "ca-app-pub-3940256099942544~3347511713";
        String adsId = (BuildConfig.APPLICATION_ID.contains("beta")) ? TestAdsId : BuildVars.ADS_ID;
        if(!BuildConfig.APPLICATION_ID.contains("plus")) {
            MobileAds.initialize(app, adsId);
        }

        FileLog.e("app initied");

        ContactsController.getInstance().checkAppAccount();
        MediaController.getInstance();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        applicationContext = getApplicationContext();
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);
        ConnectionsManager.native_setJava(Build.VERSION.SDK_INT == 14 || Build.VERSION.SDK_INT == 15);
        new ForegroundDetector(this);

        applicationHandler = new Handler(applicationContext.getMainLooper());

        startPushService();
    }

    /*public static void sendRegIdToBackend(final String token) {
        Utilities.stageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                UserConfig.pushString = token;
                UserConfig.registeredForPush = false;
                UserConfig.saveConfig(false);
                if (UserConfig.getClientUserId() != 0) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            MessagesController.getInstance().registerForPush(token);
                        }
                    });
                }
            }
        });
    }*/

    public static void startPushService() {

        // Telegram-FOSS: unconditionally enable push service
        //if (preferences.getBoolean("pushService", true)) {
        AlarmManager am = (AlarmManager) applicationContext.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(applicationContext, ApplicationLoader.class);
        pendingIntent = PendingIntent.getBroadcast(applicationContext, 0, i, 0);

        am.cancel(pendingIntent);
        am.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), 60000, pendingIntent);
        applicationContext.startService(new Intent(applicationContext, NotificationsService.class));
    }

    public static void stopPushService() {
        applicationContext.stopService(new Intent(applicationContext, NotificationsService.class));

        PendingIntent pintent = PendingIntent.getService(applicationContext, 0, new Intent(applicationContext, NotificationsService.class), 0);
        AlarmManager alarm = (AlarmManager)applicationContext.getSystemService(Context.ALARM_SERVICE);
        alarm.cancel(pintent);
        alarm.cancel(pendingIntent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}