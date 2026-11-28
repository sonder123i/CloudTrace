package com.zcshou.gogogo;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

import com.baidu.location.LocationClient;
import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.SDKInitializer;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.FileSizeBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.ChangelessFileNameGenerator;
import com.zcshou.gogogo.run.diagnostics.CrashJournal;
import com.zcshou.gogogo.run.guard.TestProviderCleaner;
import com.zcshou.gogogo.run.ui.theme.ThemeHelper;

import java.io.File;
import java.util.List;

public class GoApplication extends Application {
    public static final String APP_NAME = "CloudTrace";
    public static final String LOG_FILE_NAME = APP_NAME + ".log";
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 3; // 3 days
    private static final long MAX_LOG_FILE_BYTES = 512 * 1024L;
    /** 隐私同意偏好的唯一事实来源：prefs 文件名与 key 双重复用，WelcomeActivity 与本类共享，勿再各写一份。 */
    public static final String PREF_AGREEMENT = "KEY_ACCEPT_AGREEMENT";
    public static final String PREF_PRIVACY = "KEY_ACCEPT_PRIVACY";
    public static final String PREF_ONBOARDED = "KEY_ONBOARDED";
    private static volatile boolean sMapSdkInitialized = false;

    @Override
    public void onCreate() {
        super.onCreate();

        if (!isMainProcess()) {
            // :remote 子进程无需初始化 UI 主题、日志落盘与全局崩溃日志，避免多进程写文件竞争。
            // 但百度隐私同意 flag 是**进程级静态值**（默认 false），必须每个进程都设置：
            // 否则一旦未来在 :remote 里恢复间隔定位（LocationClient），SDK 会因
            // 「未同意隐私政策」静默拒绝工作。这里只设静态 flag，不初始化地图/日志。
            SDKInitializer.setAgreePrivacy(this, true);
            LocationClient.setAgreePrivacy(true);
            return;
        }

        // 最早安装崩溃记录：写一份本地记录后原样交还系统，不做第三方上报。
        CrashJournal.INSTANCE.install(this);

        initXlog();

        // provider 残留自愈：进程被杀/崩溃时 Service.onDestroy 不执行，
        // 可能留下被顶掉的系统 GPS provider（真实定位长期不可用）。
        TestProviderCleaner.INSTANCE.cleanupIfMainProcess(this);

        // V4.0：把用户选择的主题同步为 AppCompat 全局夜间模式，
        // 保证 res/values-night 的深海光学 Token 与手动 setTheme 的选择一致。
        // 采集必须排在这一步之前：setDefaultNightMode 会改写应用级 uiMode。
        ThemeHelper.INSTANCE.captureSystemNightMode(this);
        ThemeHelper.INSTANCE.applyGlobalNightMode(this);

        // 合规门控：仅当用户已明确同意协议与隐私政策后才初始化百度 SDK
        if (hasPrivacyConsent(this)) {
            ensureMapSdkInitialized(this);
        }
    }

    public static boolean hasPrivacyConsent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_AGREEMENT, MODE_PRIVATE);
        return prefs.getBoolean(PREF_ONBOARDED, false)
                || (prefs.getBoolean(PREF_AGREEMENT, false)
                && prefs.getBoolean(PREF_PRIVACY, false));
    }

    public static synchronized void ensureMapSdkInitialized(Context context) {
        if (sMapSdkInitialized) return;
        Context app = context.getApplicationContext();
        // 百度地图 7.5 开始，要求必须同意隐私政策，默认为 false
        SDKInitializer.setAgreePrivacy(app, true);
        LocationClient.setAgreePrivacy(true);
        SDKInitializer.setApiKey(BuildConfig.MAPS_API_KEY);
        SDKInitializer.initialize(app);
        SDKInitializer.setCoordType(CoordType.BD09LL);
        sMapSdkInitialized = true;
    }

    private boolean isMainProcess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String name = Application.getProcessName();
            return name != null && name.equals(getPackageName());
        }
        // API 27（Android 8.1）没有 Application.getProcessName：走 ActivityManager 兜底，
        // 否则 :remote 子进程也会初始化日志落盘，多进程写文件竞争
        int pid = Process.myPid();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo info : processes) {
                    if (info.pid == pid) {
                        return info.processName.equals(getPackageName());
                    }
                }
            }
        }
        return true;
    }

    /**
     * Initialize XLog.
     *
     * 日志落在内部存储（filesDir/logs）：外部 getExternalFilesDir 在 Android 8/9 上
     * 可被持有读权限的其他应用读取，与 Diagnostics 的存储策略保持同一隐私立场。
     */
    private void initXlog() {
        File logPath = new File(getFilesDir(), "logs");
        if (!logPath.isDirectory() && !logPath.mkdirs()) {
            logPath = null;
        }
        if (logPath != null) {
            int level = BuildConfig.DEBUG ? LogLevel.ALL : LogLevel.WARN;
            LogConfiguration.Builder cfgBuilder = new LogConfiguration.Builder()
                    .logLevel(level)
                    .tag(APP_NAME);
            if (BuildConfig.DEBUG) {
                cfgBuilder.enableThreadInfo()
                        .enableStackTrace(2)
                        .enableBorder();
            }
            LogConfiguration config = cfgBuilder.build();

            Printer consolePrinter = new ConsolePrinter();
            Printer filePrinter = new FilePrinter
                    .Builder(logPath.getPath())
                    .fileNameGenerator(new ChangelessFileNameGenerator(LOG_FILE_NAME))
                    .backupStrategy(new FileSizeBackupStrategy(MAX_LOG_FILE_BYTES))
                    .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))
                    .build();
            if (BuildConfig.DEBUG) {
                XLog.init(config, consolePrinter, filePrinter);
            } else {
                XLog.init(config, filePrinter);
            }
        }
    }
}