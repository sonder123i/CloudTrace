package com.zcshou.gogogo;

import android.app.Application;

import com.baidu.location.LocationClient;
import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.SDKInitializer;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.ChangelessFileNameGenerator;
import com.zcshou.gogogo.run.diagnostics.CrashJournal;
import com.zcshou.gogogo.run.guard.TestProviderCleaner;
import com.zcshou.gogogo.run.ui.theme.ThemeHelper;

import java.io.File;

public class GoApplication extends Application {
    public static final String APP_NAME = "CloudTrace";
    public static final String LOG_FILE_NAME = APP_NAME + ".log";
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 3; // 3 days

    @Override
    public void onCreate() {
        super.onCreate();

        // 最早安装崩溃记录：写一份本地记录后原样交还系统，不做第三方上报。
        CrashJournal.INSTANCE.install(this);

        initXlog();

        // provider 残留自愈：进程被杀/崩溃时 Service.onDestroy 不执行，
        // 可能留下被顶掉的系统 GPS provider（真实定位长期不可用）。
        // 内部限定只在主进程执行，避免 :remote 清掉主进程正在使用的 provider。
        TestProviderCleaner.INSTANCE.cleanupIfMainProcess(this);

        // V4.0：把用户选择的主题同步为 AppCompat 全局夜间模式，
        // 保证 res/values-night 的深海光学 Token 与手动 setTheme 的选择一致。
        ThemeHelper.INSTANCE.applyGlobalNightMode(this);

        // 百度地图 7.5 开始，要求必须同意隐私政策，默认为false
        SDKInitializer.setAgreePrivacy(this, true);
        // 百度定位 7.5 开始，要求必须同意隐私政策，默认为false(官方说可以统一为以上接口，但实际测试并不行，定位还是需要单独设置)
        LocationClient.setAgreePrivacy(true);
        SDKInitializer.setApiKey(BuildConfig.MAPS_API_KEY);
        // 在使用 SDK 各组间之前初始化 context 信息，传入 ApplicationContext
        SDKInitializer.initialize(this);

        SDKInitializer.setCoordType(CoordType.BD09LL);
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
            LogConfiguration config = new LogConfiguration.Builder()
                    .logLevel(LogLevel.ALL)
                    .tag(APP_NAME)                                         // 指定 TAG，默认为 "X-LOG"
                    .enableThreadInfo()                                    // 允许打印线程信息，默认禁止
                    .enableStackTrace(2)                                   // 允许打印深度为 2 的调用栈信息，默认禁止
                    .enableBorder()                                        // 允许打印日志边框，默认禁止
                    .build();

            Printer consolePrinter = new ConsolePrinter();                  // 通过 System.out 打印日志到控制台的打印器
            Printer filePrinter = new FilePrinter                           // 打印日志到文件的打印器
                    .Builder(logPath.getPath())                             // 指定保存日志文件的路径
                    .fileNameGenerator(new ChangelessFileNameGenerator(LOG_FILE_NAME))         // 指定日志文件名生成器，默认为 ChangelessFileNameGenerator("log")
                    .backupStrategy(new NeverBackupStrategy())              // 指定日志文件备份策略，默认为 FileSizeBackupStrategy(1024 * 1024)
                    .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))     // 指定日志文件清除策略，默认为 NeverCleanStrategy()
                    .build();
            XLog.init(config, consolePrinter, filePrinter);
        }
    }
}