package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.R;
import com.zcshou.gogogo.run.bridge.ILocationInjector;
import com.zcshou.gogogo.run.bridge.InjectorProvider;
import com.zcshou.gogogo.run.guard.EnvGuardManager;
import com.zcshou.gogogo.run.ui.home.RunHomeActivity;

public class ServiceGo extends Service implements ILocationInjector {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    /** 网络 provider 陈旧兜底 fix 的投递间隔 */
    private static final long STALE_NETWORK_FIX_INTERVAL_MS = 10_000L;
    /** 陈旧兜底 fix 的水平精度：故意放粗，让 SDK 不会优先采信 */
    private static final float STALE_NETWORK_ACCURACY_M = 60.0f;
    /** 注入挂起态下探测权限/重建 provider 的间隔 */
    private static final long SUSPEND_PROBE_INTERVAL_MS = 15_000L;
    public static final String ACTION_INJECTION_LOST = "com.zcshou.gogogo.run.ACTION_INJECTION_LOST";
    /** 显式坐标启动 extra 键（键名沿用旧 MainActivity 契约，供 adb/外部工具调试注入用） */
    public static final String LNG_MSG_ID = "LNG_VALUE";
    public static final String LAT_MSG_ID = "LAT_VALUE";
    public static final String ALT_MSG_ID = "ALT_VALUE";
    private final java.util.Random mRandom = new java.util.Random();
    private long mLastInjectionLostAt = 0L;
    /* 各 provider 的上一次 fix 时间，保证注入时间戳单调（第三方 SDK 用相邻 fix 的 time 差算速度） */
    private long mLastGpsFixTime = 0L;
    private long mLastNetFixTime = 0L;
    /** 上一次网络兜底 fix 的投递时刻（elapsedRealtime），用于 10s 节流 */
    private long mLastNetFixAt = 0L;
    /* provider 重建限频：避免每拍都 remove/add */
    private long mLastProviderRecoveryAt = 0L;
    /* GPS test provider 是否真的注册成功（未授权模拟位置时 addTestProvider 会抛异常） */
    private volatile boolean mGpsProviderReady = false;
    /* 已释放注入：此后再来的 fix 直接丢弃，避免把刚摘掉的 provider 又建回来 */
    private volatile boolean mInjectionReleased = false;
    /**
     * 注入挂起态：模拟位置授权被撤销 / provider 无法重建时进入。
     * 挂起期间不再注入、不再发广播，只按 15s 间隔探测权限，避免每拍抛异常
     * 和每 5s 广播一次造成的日志与唤醒噪声。
     */
    private volatile boolean mInjectionSuspended = false;
    private long mLastSuspendProbeAt = 0L;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // run 引擎接管标志：true 时原版摇杆注入循环停摆，坐标由 ILocationInjector.injectLocation 驱动
    private volatile boolean mEngineDriven = false;
    private volatile boolean mNotificationShown = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        ensureProviders();

        initGoLocation();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 服务被系统重启而进程未死时 onCreate 不会再跑，provider 可能已丢：
        // 先幂等补建再谈注入，避免第一条 fix 打在已失效的 provider 上。
        if (!mEngineDriven && !mGpsProviderReady) {
            ensureProviders();
        }

        // 仅当 intent 携带显式坐标时才重置位置并驱动旧注入循环；
        // 无坐标的启动（巡航/漫游绑定接管前）不允许把位置重置为默认济南值再注入，
        // 否则用户位置会先闪跳济南再跳回目标点。
        if (intent != null
                && intent.hasExtra(LNG_MSG_ID)
                && intent.hasExtra(LAT_MSG_ID)) {
            mCurLng = intent.getDoubleExtra(LNG_MSG_ID, DEFAULT_LNG);
            mCurLat = intent.getDoubleExtra(LAT_MSG_ID, DEFAULT_LAT);
            mCurAlt = intent.getDoubleExtra(ALT_MSG_ID, DEFAULT_ALT);
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
        }
        // 仅在被显式 start 时挂前台通知；漫游绑定模式由 RoamService 承担前台
        if (!mNotificationShown) {
            initNotification();
            mNotificationShown = true;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    private void initNotification() {
        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        // 点击通知回到统一首页（旧版手动界面已停用）
        Intent clickIntent = new Intent(this, RunHomeActivity.class);
        clickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .setSmallIcon(R.drawable.ic_stat_cloudtrace)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop && !mEngineDriven) {
                        injectStaleNetworkFix();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };
        // 不在 onCreate 自启动注入循环：等待显式坐标（onStartCommand / setPosition）
        // 或引擎 setEngineDriven 接管后再驱动，避免以默认坐标空注
    }

    /**
     * 幂等重建：服务重启 / provider 被系统回收后自愈。
     *
     * @return GPS provider 是否真的就绪。未授权模拟位置时 addTestProvider
     *         会抛异常，此时返回 false，调用方据此立即上报注入丢失，
     *         而不是静默等下一拍。
     */
    private boolean ensureProviders() {
        removeTestProviderNetwork();
        addTestProviderNetwork();
        removeTestProviderGPS();
        addTestProviderGPS();
        return mGpsProviderReady;
    }

    private void removeTestProviderGPS() {
        // 不先调 setTestProviderEnabled(false)：对真实 provider 该调用会抛异常，
        // 个别 ROM 上还会真的把系统 GPS 关掉。removeTestProvider 本身即可摘除。
        mGpsProviderReady = false;
        try {
            mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {
            // provider 未处于 test 模式（绝大多数情况：本身就没有残留）
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
            mGpsProviderReady = true;
        } catch (Exception e) {
            mGpsProviderReady = false;
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        if (mInjectionReleased) return;
        if (mInjectionSuspended && !tryResumeFromSuspension()) return;
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(noisyAccuracy());          // 3.5~10.5m 正态波动，避免固定精度穿帮
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            mLastGpsFixTime = monotonicFixTime(mLastGpsFixTime);
            loc.setTime(mLastGpsFixTime);              // 单调时间，避免 SDK 算出负的时间差
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            fillGnssExtras(loc);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (SecurityException e) {
            notifyInjectionLost();
            XLog.e("SERVICEGO: ERROR - setLocationGPS permission");
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {
            // 同上：非 test 模式无需处理
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    /**
     * 网络 provider 只喂「陈旧且粗」的兜底 fix：精度 60m、时间落后 3s。
     *
     * 目的不是让 SDK 采信，而是给它一个可回退的结果：
     * - 只吃网络定位的目标 App 不会「完全不动」；
     * - SDK 会因为精度差、时间旧而优先采用 GPS fix，不会把 network 结果
     *   当成「非 GPS 定位 / GPS 信号弱」；
     * - 也不会出现假网络坐标与真实基站/Wi-Fi 指纹大面积矛盾。
     */
    private void injectStaleNetworkFix() {
        if (mLocManager == null) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed - mLastNetFixAt < STALE_NETWORK_FIX_INTERVAL_MS) return;
        mLastNetFixAt = nowElapsed;
        try {
            Location stale = new Location(LocationManager.NETWORK_PROVIDER);
            stale.setLatitude(mCurLat);
            stale.setLongitude(mCurLng);
            stale.setAltitude(mCurAlt);
            stale.setSpeed((float) mSpeed);
            stale.setBearing(mCurBea);
            stale.setAccuracy(STALE_NETWORK_ACCURACY_M);
            stale.setVerticalAccuracyMeters(30.0f);
            stale.setSpeedAccuracyMetersPerSecond(2.0f);
            stale.setBearingAccuracyDegrees(30.0f);
            mLastNetFixTime = Math.max(mLastNetFixTime + 1L, System.currentTimeMillis() - 3000L);
            stale.setTime(mLastNetFixTime);
            // 与 time 一致地落后 3s，避免两个时间源互相矛盾
            stale.setElapsedRealtimeNanos(Math.max(1L, SystemClock.elapsedRealtimeNanos() - 3_000_000_000L));
            stale.setExtras(null);   // 网络 fix 没有卫星/CN0，别伪造
            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, stale);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - injectStaleNetworkFix");
        }
    }

    // ==================== ILocationInjector 桥梁实现（run 引擎专用） ====================

    @Override
    public void injectLocation(double latitude, double longitude, double altitude,
                               float speedMps, float bearingDegrees, float accuracyMeters,
                               float verticalAccuracyMeters, float speedAccuracyMps,
                               float bearingAccuracyDegrees) {
        if (mInjectionReleased) return;
        if (mInjectionSuspended && !tryResumeFromSuspension()) return;
        mCurLat = latitude;
        mCurLng = longitude;
        mCurAlt = altitude;
        mCurBea = bearingDegrees;
        mSpeed = speedMps;
        try {
            long elapsedNanos = SystemClock.elapsedRealtimeNanos();

            Location gps = buildEngineLocation(LocationManager.GPS_PROVIDER, latitude, longitude,
                    altitude, speedMps, bearingDegrees, accuracyMeters, verticalAccuracyMeters,
                    speedAccuracyMps, bearingAccuracyDegrees, mLastGpsFixTime, elapsedNanos);
            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, gps);
            mLastGpsFixTime = gps.getTime();

            // 网络 provider 只给陈旧兜底（10s 一次），主力仍是 GPS
            if (mLocManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                injectStaleNetworkFix();
            }
        } catch (SecurityException e) {
            // provider 可能被系统回收：限频重建，下一拍自动恢复；
            // 重建不了（多半是模拟位置授权被撤销）则挂起，交给探测逻辑恢复
            if (recoverProviders()) {
                XLog.i("SERVICEGO: test provider 丢失，已重建");
            } else {
                enterSuspension();
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - injectLocation");
        }
    }

    private Location buildEngineLocation(String provider, double latitude, double longitude,
                                         double altitude, float speedMps, float bearingDegrees,
                                         float accuracyMeters, float verticalAccuracyMeters,
                                         float speedAccuracyMps, float bearingAccuracyDegrees,
                                         long providerLastFixTime, long elapsedNanos) {
        Location loc = new Location(provider);
        loc.setLatitude(latitude);
        loc.setLongitude(longitude);
        loc.setAltitude(altitude);
        loc.setSpeed(speedMps);
        loc.setBearing(bearingDegrees);
        // 精度以引擎传入值为准（引擎侧做随机游走），缺失时才本地兜底
        loc.setAccuracy(accuracyMeters > 0f ? accuracyMeters : noisyAccuracy());
        loc.setVerticalAccuracyMeters(verticalAccuracyMeters);
        loc.setSpeedAccuracyMetersPerSecond(speedAccuracyMps);
        loc.setBearingAccuracyDegrees(bearingAccuracyDegrees);
        loc.setTime(monotonicFixTime(providerLastFixTime));
        loc.setElapsedRealtimeNanos(elapsedNanos);
        fillGnssExtras(loc);
        return loc;
    }

    /** 3.5~10.5m 的正态波动水平精度，避免每次固定 1.0m 被识别为模拟信号 */
    private float noisyAccuracy() {
        float value = 6.5f + (float) mRandom.nextGaussian() * 1.5f;
        return Math.max(3.5f, Math.min(10.5f, value));
    }

    /**
     * fix 时间必须单调：第三方 SDK 会拿相邻 fix 的 time 差算速度/判断定位稳定性。
     * 首拍或时钟跳变（|Δ| > 5s，用户改时间/长时间挂起）时重置为当前时间，
     * 避免把 time 永久「顶到未来」后出现负时间差。
     */
    private long monotonicFixTime(long providerLastFixTime) {
        long now = System.currentTimeMillis();
        if (providerLastFixTime <= 0L || Math.abs(now - providerLastFixTime) > 5000L) {
            return now;
        }
        return Math.max(providerLastFixTime + 1L, now);
    }

    /** 真实 framework 会为 GNSS fix 填 satellites / meanCn0 / maxCn0 三个 extras 键 */
    private void fillGnssExtras(Location loc) {
        int satellites = 12 + mRandom.nextInt(6);
        float meanCn0 = 26f + mRandom.nextFloat() * 10f;
        Bundle bundle = new Bundle();
        bundle.putInt("satellites", satellites);
        bundle.putFloat("meanCn0", meanCn0);
        bundle.putFloat("maxCn0", Math.max(meanCn0, 38f + mRandom.nextFloat() * 8f));
        loc.setExtras(bundle);
    }

    /**
     * provider 被系统回收后限频重建（10s 一次）。
     *
     * @return 重建成功（GPS provider 已就绪）才返回 true；被限频或重建失败都返回 false，
     *         调用方据此决定是否上报注入丢失——未授权时不会静默吞掉错误。
     */
    private boolean recoverProviders() {
        if (mInjectionReleased) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastProviderRecoveryAt < 10_000L) {
            return false;
        }
        mLastProviderRecoveryAt = now;
        return ensureProviders();
    }

    /**
     * 进入挂起态：只在这里上报一次注入丢失，之后的 fix 直接丢弃，
     * 由 {@link #tryResumeFromSuspension()} 按 15s 间隔探测能否恢复。
     */
    private void enterSuspension() {
        if (mInjectionSuspended) return;
        mInjectionSuspended = true;
        mLastSuspendProbeAt = SystemClock.elapsedRealtime();
        XLog.e("SERVICEGO: 注入挂起（模拟位置不可用），已暂停注入并停止重试");
        notifyInjectionLost();
    }

    /**
     * 挂起态探测：先查 appops，权限恢复才重建 provider，避免无谓的
     * addTestProvider 异常日志。恢复成功后本次 fix 正常投递。
     */
    private boolean tryResumeFromSuspension() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastSuspendProbeAt < SUSPEND_PROBE_INTERVAL_MS) return false;
        mLastSuspendProbeAt = now;
        if (!EnvGuardManager.INSTANCE.isMockLocationAllowed(this)) return false;
        if (!ensureProviders()) return false;
        mInjectionSuspended = false;
        XLog.i("SERVICEGO: 模拟位置已恢复，注入继续");
        return true;
    }

    private void notifyInjectionLost() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastInjectionLostAt < 5000L) return;
        mLastInjectionLostAt = now;
        try {
            Intent intent = new Intent(ACTION_INJECTION_LOST);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - notifyInjectionLost");
        }
    }

    @Override
    public void setEngineDriven(boolean enabled) {
        mEngineDriven = enabled;
        mInjectionReleased = false;
        // 新会话重新开始：清掉上一轮遗留的挂起态，立即允许一次探测
        mInjectionSuspended = false;
        mLastSuspendProbeAt = 0L;
        if (enabled) {
            // 上一轮 releaseInjection 已移除测试 Provider，同一 Service 实例再次接管前需幂等重建
            ensureProviders();
            mLocHandler.removeMessages(HANDLER_MSG_ID);
        } else {
            // 旧摇杆浮窗已整体清退（漫游摇杆/悬浮胶囊接管），false 分支只恢复注入循环
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
        }
    }

    @Override
    public void releaseInjection() {
        mEngineDriven = false;
        mInjectionReleased = true;
        mInjectionSuspended = false;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        removeTestProviderNetwork();
        removeTestProviderGPS();
    }

    public class ServiceGoBinder extends Binder implements InjectorProvider {
        /** run 引擎通过 Binder 拿到注入桥梁实例（bridge.InjectorProvider 契约） */
        @Override
        public ILocationInjector getInjector() {
            return ServiceGo.this;
        }

        public void setPosition(double lng, double lat, double alt) {
            if (mEngineDriven) {
                return;
            }
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
        }
    }
}


