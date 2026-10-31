package io.github.howard20181.hyperos.fcmlive;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerExemptionManager;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedModule;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {
    private static final String TAG = "HyperGreeze";
    private static final List<String> CN_DEFER_BROADCAST = Arrays.asList("com.google.android.intent.action.GCM_RECONNECT", "com.google.android.gcm.DISCONNECTED", "com.google.android.gcm.CONNECTED", "com.google.android.gms.gcm.HEARTBEAT_ALARM");
    private static final String ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE";
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent";
    private static final long[] FCM_SCAN_RETRY_SECONDS = {1, 2, 4, 8, 16, 32};
    private static final long[] RECEIVER_INIT_RETRY_SECONDS = {2, 4, 8, 16, 30, 30};
    private Pair<String, ClassLoader> param;
    private final Set<String> hookedIds = new HashSet<>();
    private volatile Context systemContext;
    private ScheduledExecutorService fcmPackageScanner = newFcmPackageScanner();
    private ScheduledExecutorService policyReceiverInitializer = newPolicyReceiverInitializer();
    private final AtomicBoolean policyReceiverInitStarted = new AtomicBoolean();
    private final AtomicBoolean fcmScanQueued = new AtomicBoolean();
    private final AtomicBoolean fcmScanRequested = new AtomicBoolean();
    private final AtomicInteger fcmScanRetryCount = new AtomicInteger();
    private final AtomicLong fcmScanRetryEpoch = new AtomicLong();
    private final Object fcmScanRetryLock = new Object();
    private ScheduledFuture<?> fcmScanRetryFuture;
    private volatile long fcmScannerGeneration;

    private static ScheduledExecutorService newFcmPackageScanner() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "HyperFCM-FcmPolicy");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ScheduledExecutorService newPolicyReceiverInitializer() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "HyperFCM-PolicyReceivers");
            thread.setDaemon(true);
            return thread;
        });
    }

    private synchronized void ensurePolicyLifecycleExecutors() {
        if (fcmPackageScanner == null || fcmPackageScanner.isShutdown()) {
            fcmPackageScanner = newFcmPackageScanner();
        }
        if (policyReceiverInitializer == null || policyReceiverInitializer.isShutdown()) {
            policyReceiverInitializer = newPolicyReceiverInitializer();
        }
    }

    private synchronized void cleanupPolicyLifecycle() {
        Context context = systemContext;
        unregisterReceiver(context, policyReceiver);
        unregisterReceiver(context, packageReceiver);
        policyReceiver = null;
        packageReceiver = null;
        allowlistReceiverRegistered = false;
        packageReceiverRegistered = false;
        policyReceiverInitStarted.set(false);
        synchronized (fcmScanRetryLock) {
            fcmScannerGeneration++;
            fcmScanRetryEpoch.incrementAndGet();
            fcmScanRetryCount.set(0);
            cancelFcmScanRetryLocked();
        }
        fcmScanRequested.set(false);
        fcmScanQueued.set(false);
        if (policyReceiverInitializer != null) {
            policyReceiverInitializer.shutdownNow();
        }
        if (fcmPackageScanner != null) {
            fcmPackageScanner.shutdownNow();
        }
    }

    private void cancelFcmScanRetryLocked() {
        if (fcmScanRetryFuture != null) {
            fcmScanRetryFuture.cancel(false);
            fcmScanRetryFuture = null;
        }
    }

    private static void unregisterReceiver(Context context, BroadcastReceiver receiver) {
        if (context == null || receiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Registration may have failed before hot reload began.
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to unregister policy receiver during hot reload", e);
        }
    }

    private HookBuilder hookE(Executable executable) {
        var builder = hook(executable);

        if (getApiVersion() >= 102) {
            var id = executable.toGenericString();
            builder.setId(id);
            hookedIds.add(id);
        }

        return builder;
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        UnsafeUtils.INSTANCE.setXposedModule(this);
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        this.param = Pair.create("system", classLoader);
        try {
            hookSystemServer(classLoader);
        } catch (Throwable tr) {
            log(Log.ERROR, TAG, "Failed to hook SystemServer", tr);
        }
    }

    private void hookSystemServer(ClassLoader classLoader) {
        try {
            hookAllowlist();
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook allowlist receiver", t);
        }
        try {
            hookGreezeManagerService(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService", t);
        }
        try {
            hookDomesticPolicyManager(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook DomesticPolicyManager", t);
        }
        try {
            hookListAppsManager(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook ListAppsManager", t);
        }
        try {
            hookBroadcastQueueModernStubImpl(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook BroadcastQueueModernStubImpl", e);
        }
        try {
            hookProcessPolicy(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook ProcessPolicy", e);
        }
        try {
            hookAwareResourceControl(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook AwareResourceControl", e);
        }
        try {
            hookActivityManagerService(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook ActivityManagerService", e);
        }
        try {
            hookInternationalPolicyManager(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook InternationalPolicyManager", e);
        }
        try {
            hookProcessCleanerBase(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook ProcessCleanerBase", e);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        var packageName = param.getPackageName();
        var classLoader = param.getClassLoader();
        this.param = Pair.create(packageName, classLoader);
        try {
            hookPackage(packageName, classLoader);
        } catch (Throwable tr) {
            log(Log.ERROR, TAG, "Failed to hook package", tr);
        }
    }

    private void hookPackage(String packageName, ClassLoader classLoader) {
        if ("com.miui.powerkeeper".equals(packageName)) {
            try {
                hookGmsObserver(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook GmsObserver", e);
            }
            try {
                hookGlobalFeatureConfigureHelper(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", e);
            }
        }
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        // Hot reload of system_server hooks is unreliable; a full reboot is the
        // supported path. We still support reload below, but advise rebooting.
        log(Log.WARN, TAG, "Hot reload requested — a full reboot is recommended for reliability");
        cleanupPolicyLifecycle();
        param.setSavedInstanceState(this.param);
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        UnsafeUtils.INSTANCE.setXposedModule(this);
        ensurePolicyLifecycleExecutors();
        // Clean reload: reset id bookkeeping and remove every previous hook so the
        // re-setup below starts fresh. Without this, old handles were never unhooked
        // (hookedIds accumulated across passes) and stacked duplicate hooks made the
        // reload appear ineffective.
        hookedIds.clear();
        param.getOldHookHandles().forEach(h -> {
            try {
                h.unhook();
            } catch (Throwable ignored) {
            }
        });
        if (param.getSavedInstanceState() instanceof Pair<?, ?> pair
                && pair.first instanceof String packageName
                && pair.second instanceof ClassLoader classLoader) {
            this.param = Pair.create(packageName, classLoader);
            try {
                if (param.isSystemServer()) {
                    hookSystemServer(classLoader);
                } else {
                    hookPackage(packageName, classLoader);
                }
            } catch (Throwable tr) {
                log(Log.ERROR, TAG, "Hot reload failed", tr);
            }
        }
    }

    private Method optionalMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            log(Log.INFO, TAG, "Optional ROM method unavailable: " + owner.getName() + "." + name);
            return null;
        }
    }

    private Field optionalField(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Try the next name used by another ROM version.
            }
        }
        log(Log.INFO, TAG, "Optional ROM field unavailable: " + owner.getName() + "." + String.join("/", names));
        return null;
    }

    private Class<?> optionalClass(ClassLoader loader, String name) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException ignored) {
            log(Log.INFO, TAG, "Optional ROM class unavailable: " + name);
            return null;
        }
    }

    // Adapted from upstream PR #31 (E3N-glotm), retaining this branch's hook identities.
    private void hookGreezeManagerService(ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> service = classLoader.loadClass("com.miui.server.greeze.GreezeManagerService");
        Method uidLookup = optionalMethod(service, "getPackageNameFromUid", int.class);
        if (uidLookup != null) uidLookup.setAccessible(true);
        Method broadcast = optionalMethod(service, "isAllowBroadcast",
                int.class, String.class, int.class, String.class, String.class);
        if (broadcast != null) {
            hookE(broadcast).intercept(chain -> {
                String callee = chain.getArg(3) instanceof String processName ? processName : null;
                if (uidLookup != null && chain.getArg(2) instanceof Integer uid) {
                    try {
                        Object packageName = getInvoker(uidLookup).invoke(chain.getThisObject(), uid);
                        if (packageName instanceof String name) callee = name;
                    } catch (Exception exception) {
                        log(Log.WARN, TAG, "Greeze UID lookup failed; retaining callee process name", exception);
                    }
                }
                if (chain.getArg(4) instanceof String action
                        && ((chain.getArg(1) instanceof String caller && GMS_PACKAGE_NAME.equals(caller)
                        && ACTION_REMOTE_INTENT.equals(action))
                        || ((GMS_PACKAGE_NAME.equals(callee) || GMS_PERSISTENT_PROCESS_NAME.equals(callee))
                        && CN_DEFER_BROADCAST.contains(action)))) {
                    return true;
                }
                return chain.proceed();
            });
            deoptimize(broadcast);
        }

        Method defer = optionalMethod(service, "deferBroadcastForMiui", String.class);
        if (defer != null) {
            hookE(defer).intercept(chain -> {
                if (chain.getArg(0) instanceof String action && CN_DEFER_BROADCAST.contains(action)) return false;
                return chain.proceed();
            });
            deoptimize(defer);
        }

        Method trigger = optionalMethod(service, "triggerGMSLimitAction", boolean.class);
        if (trigger == null) trigger = optionalMethod(service, "triggerGMSLimitAction");
        if (trigger != null) {
            Method selected = trigger;
            Field limit = selected.getParameterCount() == 0 ? optionalField(service, "mGmsLimitEnabled") : null;
            hookE(selected).intercept(chain -> {
                if (!chain.getArgs().isEmpty()) {
                    Object[] args = chain.getArgs().toArray();
                    args[0] = false;
                    return chain.proceed(args);
                }
                if (limit != null) UnsafeUtils.INSTANCE.setBooleanField(limit, chain.getThisObject(), false);
                return chain.proceed();
            });
            deoptimize(selected);
        }
    }

    private void hookDomesticPolicyManager(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        Class<?> policy = classLoader.loadClass("com.miui.server.greeze.DomesticPolicyManager");
        Method defer = policy.getDeclaredMethod("deferBroadcast", String.class);
        hookE(defer).intercept(chain -> {
            if (chain.getArg(0) instanceof String action
                    && (ACTION_REMOTE_INTENT.equals(action) || CN_DEFER_BROADCAST.contains(action))) return false;
            return chain.proceed();
        });
        deoptimize(defer);
    }

    private void hookListAppsManager(ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> lists = classLoader.loadClass("com.miui.server.greeze.power.ListAppsManager");
        Field blacklist = optionalField(lists, "mSystemBlackList", "SYSTEM_BLACK_LIST");
        if (blacklist != null) {
            for (var constructor : lists.getDeclaredConstructors()) {
                hookE(constructor).intercept(chain -> {
                    try {
                        return chain.proceed();
                    } finally {
                        try {
                            if (blacklist.get(chain.getThisObject()) instanceof List<?> packages) {
                                packages.remove(GMS_PACKAGE_NAME);
                            }
                        } catch (Exception exception) {
                            log(Log.WARN, TAG, "Failed to update ListAppsManager blacklist", exception);
                        }
                    }
                });
                deoptimize(constructor);
            }
        }

        Method isInWhiteList = optionalMethod(lists, "isInWhiteList", String.class);
        Field whitelist = optionalField(lists, "mUseDataWhiteList", "USE_DATA_WHITE_LIST");
        if (isInWhiteList != null && whitelist != null) {
            hookE(isInWhiteList).intercept(chain -> {
                try {
                    if (whitelist.get(chain.getThisObject()) instanceof Set<?> packages) {
                        @SuppressWarnings("unchecked")
                        Set<String> names = (Set<String>) packages;
                        names.add(GMS_PACKAGE_NAME);
                    }
                } catch (Exception exception) {
                    log(Log.WARN, TAG, "Failed to update ListAppsManager whitelist", exception);
                }
                return chain.proceed();
            });
            deoptimize(isInWhiteList);
        }
    }

    private void hookBroadcastQueueModernStubImpl(ClassLoader classLoader) throws
            ClassNotFoundException, NoSuchMethodException, NoSuchFieldException {
        var BroadcastQueueModernStubImplClass = classLoader.loadClass("com.android.server.am.BroadcastQueueModernStubImpl");
        var BroadcastQueueClass = classLoader.loadClass("com.android.server.am.BroadcastQueue");
        var BroadcastRecordClass = classLoader.loadClass("com.android.server.am.BroadcastRecord");
        var callerPackageField = BroadcastRecordClass.getDeclaredField("callerPackage");
        callerPackageField.setAccessible(true);
        var intentField = BroadcastRecordClass.getDeclaredField("intent");
        intentField.setAccessible(true);
        var checkApplicationAutoStartMethod = BroadcastQueueModernStubImplClass.getDeclaredMethod("checkApplicationAutoStart", BroadcastQueueClass, BroadcastRecordClass, ResolveInfo.class);
        hookE(checkApplicationAutoStartMethod).intercept(chain -> {
            try {
                var broadcastRecord = chain.getArg(1);
                if (callerPackageField.get(broadcastRecord) instanceof String callerPackage
                        && GMS_PACKAGE_NAME.equals(callerPackage) // BroadcastRecord.callerPackage nullable
                        && intentField.get(broadcastRecord) instanceof Intent intent
                        && ACTION_REMOTE_INTENT.equals(intent.getAction())
                        // Auto-start only apps allowed by the manual or automatic FCM policy.
                        && intent.getPackage() instanceof String targetPackage
                        && shouldWake(targetPackage)) {
                    return true;
                }
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to modify BroadcastQueueModernStubImpl#checkApplicationAutoStart", e);
            }
            return chain.proceed();
        });
        deoptimize(checkApplicationAutoStartMethod);
    }

    private void hookProcessPolicy(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var ProcessPolicyClass = classLoader.loadClass("com.android.server.am.ProcessPolicy");
        var getWhiteListMethod = ProcessPolicyClass.getDeclaredMethod("getWhiteList", int.class);
        hookE(getWhiteListMethod).intercept(chain -> {
            var result = chain.proceed();
            if (chain.getArg(0) instanceof Integer flags && (flags & 1) != 0) {
                if (result instanceof List<?> original) {
                    List<Object> whiteList = new ArrayList<>(original);
                    if (!whiteList.contains(GMS_PACKAGE_NAME)) whiteList.add(GMS_PACKAGE_NAME);
                    if (!whiteList.contains(GMS_PERSISTENT_PROCESS_NAME)) whiteList.add(GMS_PERSISTENT_PROCESS_NAME);
                    return whiteList;
                }
            }
            return result;
        });
    }

    private void hookAwareResourceControl(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchFieldException {
        var AwareResourceControlClass = classLoader.loadClass("com.miui.server.greeze.power.AwareResourceControl");
        var mNoNetworkBlackUidsField = AwareResourceControlClass.getDeclaredField("mNoNetworkBlackUids");
        mNoNetworkBlackUidsField.setAccessible(true);
        var AwareResourceControlConstructors = AwareResourceControlClass.getDeclaredConstructors();
        for (var constructor : AwareResourceControlConstructors) {
            hookE(constructor).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    try {
                        var mNoNetworkBlackUids = (List<String>) mNoNetworkBlackUidsField.get(chain.getThisObject());
                        if (mNoNetworkBlackUids != null) {
                            mNoNetworkBlackUids.remove(GMS_PACKAGE_NAME);
                        }
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "Failed to modify AwareResourceControl.mNoNetworkBlackUids", e);
                    }
                }
            });
            deoptimize(constructor);
        }
    }

    // Upstream PR #30 by 793354162, also included in PR #31 by E3N-glotm.
    private void hookGmsObserver(ClassLoader classLoader) {
        Class<?> netd = optionalClass(classLoader, "com.miui.powerkeeper.utils.NetdExecutor");
        if (netd != null) {
            Method init = optionalMethod(netd, "initGmsChain", String.class, int.class, String.class);
            if (init != null) {
                hookE(init).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    args[2] = "ACCEPT";
                    return chain.proceed(args);
                });
                deoptimize(init);
            }
        }

        Class<?> observer = optionalClass(classLoader, "com.miui.powerkeeper.utils.GmsObserver");
        if (observer == null) return;
        for (String name : new String[]{"updateGmsAlarm", "updateGmsNetWork",
                "updateGoogleReletivesWakelock", "updateFrameworkGmsNetStatus"}) {
            Method method = optionalMethod(observer, name, boolean.class);
            if (method == null) continue;
            hookE(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                args[0] = false;
                return chain.proceed(args);
            });
            deoptimize(method);
        }
    }

    private void hookGlobalFeatureConfigureHelper(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        var GlobalFeatureConfigureHelperClass = classLoader.loadClass("com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper");
        var getDozeWhiteListAppsMethod = GlobalFeatureConfigureHelperClass.getDeclaredMethod("getDozeWhiteListApps", Bundle.class);
        hookE(getDozeWhiteListAppsMethod).intercept(chain -> {
            var result = chain.proceed();
            if (result instanceof List<?>) {
                var whiteList = (List<String>) result;
                if (!whiteList.contains(GMS_PACKAGE_NAME)) {
                    whiteList.add(GMS_PACKAGE_NAME);
                }
            }
            return result;
        });
    }

    private static PowerExemptionManager powerExemptionManager = null;

    @RequiresApi(Build.VERSION_CODES.S)
    private static PowerExemptionManager getPowerExemptionManager(Context context) {
        if (powerExemptionManager == null) {
            // mContext.getSystemService("power_exemption")
            powerExemptionManager = new PowerExemptionManager(context);
        }
        return powerExemptionManager;
    }

    /** A Context owned by the system-server ActivityThread. */
    private Context getSystemContext() {
        if (systemContext == null) {
            Class<?> activityThreadClass = null;
            try {
                activityThreadClass = Class.forName("android.app.ActivityThread");
                Method currentApplication = activityThreadClass.getMethod("currentApplication");
                if (currentApplication.invoke(null) instanceof Context ctx) {
                    systemContext = ctx;
                }
            } catch (Throwable ignored) {
            }
            if (systemContext == null && activityThreadClass != null) {
                try {
                    Method currentActivityThread =
                            activityThreadClass.getDeclaredMethod("currentActivityThread");
                    currentActivityThread.setAccessible(true);
                    Object activityThread = currentActivityThread.invoke(null);
                    if (activityThread != null) {
                        Method getSystemContext =
                                activityThreadClass.getDeclaredMethod("getSystemContext");
                        getSystemContext.setAccessible(true);
                        if (getSystemContext.invoke(activityThread) instanceof Context ctx) {
                            systemContext = ctx;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return systemContext;
    }

    /** Manual and automatic portions of the policy are published as one snapshot. */
    private static final class WakePolicy {
        final Set<String> manualAllowlist;
        final boolean autoFcm;

        WakePolicy(Set<String> manualAllowlist, boolean autoFcm) {
            this.manualAllowlist = Collections.unmodifiableSet(new HashSet<>(manualAllowlist));
            this.autoFcm = autoFcm;
        }
    }

    private static volatile WakePolicy sWakePolicy = new WakePolicy(Collections.emptySet(), false);
    private static volatile Set<String> sDetectedFcmPackages = Collections.emptySet();
    private static volatile boolean sFcmPackageCacheLoaded;

    /** Re-read the complete policy from shared remote preferences. */
    private void loadPolicyFromRemotePrefs() {
        try {
            var prefs = getRemotePreferences(Prefs.GROUP_CONFIG);
            Set<String> set = prefs.getStringSet(Prefs.KEY_ALLOWLIST, Collections.emptySet());
            WakePolicy previousPolicy = sWakePolicy;
            WakePolicy policy = new WakePolicy(set != null ? set : Collections.emptySet(),
                    prefs.getBoolean(Prefs.KEY_AUTO_FCM, false));
            sWakePolicy = policy;
            if (policy.autoFcm && (!previousPolicy.autoFcm || !sFcmPackageCacheLoaded)) {
                requestFcmPackageScan();
            }
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to read FCM wake policy", e);
        }
    }

    /** Called from hookSystemServer: load the initial policy at boot. */
    private void hookAllowlist() {
        loadPolicyFromRemotePrefs();
        schedulePolicyReceiverInitialization();
    }

    private boolean allowlistReceiverRegistered = false;
    private boolean packageReceiverRegistered = false;
    private BroadcastReceiver policyReceiver;
    private BroadcastReceiver packageReceiver;

    private boolean shouldWake(String targetPackage) {
        ensurePolicyReceivers();
        WakePolicy policy = sWakePolicy;
        return FcmPolicy.shouldWake(targetPackage, policy.manualAllowlist,
                policy.autoFcm, sDetectedFcmPackages);
    }

    /** Register policy and package-change receivers after system_server is ready. */
    private synchronized void ensurePolicyReceivers() {
        Context sys = getSystemContext();
        if (sys == null) {
            return;
        }
        boolean refreshPolicy = !allowlistReceiverRegistered;
        boolean packageReceiverWasRegistered = packageReceiverRegistered;
        if (!allowlistReceiverRegistered) {
            try {
                policyReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (Prefs.ACTION_ALLOWLIST_CHANGED.equals(intent.getAction())) {
                            loadPolicyFromRemotePrefs();
                        }
                    }
                };
                IntentFilter filter = new IntentFilter(Prefs.ACTION_ALLOWLIST_CHANGED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    sys.registerReceiver(policyReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    sys.registerReceiver(policyReceiver, filter);
                }
                allowlistReceiverRegistered = true;
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to register policy receiver", e);
            }
        }
        if (!packageReceiverRegistered) {
            try {
                packageReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                                && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            return;
                        }
                        if (sWakePolicy.autoFcm) {
                            requestFcmPackageScan();
                        }
                    }
                };
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_PACKAGE_ADDED);
                filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
                filter.addDataScheme("package");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    sys.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    sys.registerReceiver(packageReceiver, filter);
                }
                packageReceiverRegistered = true;
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to register package receiver", e);
            }
        }
        // The app may have changed its preferences before this lazy receiver was
        // installed, so catch up from the latest committed policy once.
        if (refreshPolicy) {
            loadPolicyFromRemotePrefs();
        }
        if (!packageReceiverWasRegistered && packageReceiverRegistered && sWakePolicy.autoFcm
                && !sFcmPackageCacheLoaded) {
            requestFcmPackageScan();
        }
    }

    /** Retry receiver setup off the startup thread until system_server is ready. */
    private void schedulePolicyReceiverInitialization() {
        if (!policyReceiverInitStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            policyReceiverInitializer.schedule(() -> attemptPolicyReceiverInitialization(0),
                    2, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            policyReceiverInitStarted.set(false);
            log(Log.ERROR, TAG, "Failed to schedule policy receiver initialization", e);
        }
    }

    private void attemptPolicyReceiverInitialization(int retryIndex) {
        ensurePolicyReceivers();
        if (allowlistReceiverRegistered && packageReceiverRegistered) {
            policyReceiverInitializer.shutdown();
            return;
        }
        if (retryIndex >= RECEIVER_INIT_RETRY_SECONDS.length) {
            log(Log.WARN, TAG, "Policy receiver setup did not complete during startup retry window");
            policyReceiverInitializer.shutdown();
            return;
        }
        try {
            policyReceiverInitializer.schedule(
                    () -> attemptPolicyReceiverInitialization(retryIndex + 1),
                    RECEIVER_INIT_RETRY_SECONDS[retryIndex], TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            policyReceiverInitStarted.set(false);
            log(Log.ERROR, TAG, "Failed to retry policy receiver initialization", e);
        }
    }

    /**
     * Queue a coalesced package scan. PackageManager work stays off hook and
     * broadcast threads; a second request during a scan causes one follow-up.
     */
    private void requestFcmPackageScan() {
        synchronized (fcmScanRetryLock) {
            cancelFcmScanRetryLocked();
            fcmScanRetryCount.set(0);
            fcmScanRetryEpoch.incrementAndGet();
        }
        queueFcmPackageScan(fcmScannerGeneration);
    }

    private void queueFcmPackageScan(long generation) {
        fcmScanRequested.set(true);
        if (!fcmScanQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            fcmPackageScanner.execute(() -> {
                try {
                    while (generation == fcmScannerGeneration
                            && fcmScanRequested.getAndSet(false)) {
                        if (refreshDetectedFcmPackages(generation)) {
                            synchronized (fcmScanRetryLock) {
                                if (generation == fcmScannerGeneration) {
                                    fcmScanRetryCount.set(0);
                                    fcmScanRetryEpoch.incrementAndGet();
                                    cancelFcmScanRetryLocked();
                                }
                            }
                        } else {
                            scheduleFcmPackageScanRetry(generation,
                                    fcmScanRetryEpoch.get());
                        }
                    }
                } finally {
                    if (generation == fcmScannerGeneration) {
                        fcmScanQueued.set(false);
                        if (fcmScanRequested.get()) {
                            queueFcmPackageScan(generation);
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            if (generation == fcmScannerGeneration) {
                fcmScanQueued.set(false);
                scheduleFcmPackageScanRetry(generation, fcmScanRetryEpoch.get());
            }
            log(Log.ERROR, TAG, "Failed to queue FCM package scan", e);
        }
    }

    private void scheduleFcmPackageScanRetry(long generation, long retryEpoch) {
        synchronized (fcmScanRetryLock) {
            if (generation != fcmScannerGeneration
                    || retryEpoch != fcmScanRetryEpoch.get()
                    || fcmScanRetryFuture != null) {
                return;
            }
            int retryIndex = fcmScanRetryCount.getAndIncrement();
            if (retryIndex >= FCM_SCAN_RETRY_SECONDS.length) {
                log(Log.WARN, TAG, "FCM package scan retry limit reached; waiting for the next policy or package change");
                return;
            }
            try {
                final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
                ScheduledFuture<?> future = fcmPackageScanner.schedule(() -> {
                    synchronized (fcmScanRetryLock) {
                        if (fcmScanRetryFuture != futureHolder[0]) {
                            return;
                        }
                        fcmScanRetryFuture = null;
                        if (generation != fcmScannerGeneration
                                || retryEpoch != fcmScanRetryEpoch.get()) {
                            return;
                        }
                    }
                    queueFcmPackageScan(generation);
                }, FCM_SCAN_RETRY_SECONDS[retryIndex], TimeUnit.SECONDS);
                futureHolder[0] = future;
                fcmScanRetryFuture = future;
            } catch (RuntimeException e) {
                log(Log.ERROR, TAG, "Failed to schedule FCM package scan retry", e);
            }
        }
    }

    private boolean refreshDetectedFcmPackages(long generation) {
        try {
            Context sys = getSystemContext();
            if (sys == null) {
                return false;
            }
            android.content.pm.PackageManager pm = sys.getPackageManager();
            FcmDetector detector = new FcmDetector(pm);
            Set<String> detected = new HashSet<>();
            for (android.content.pm.PackageInfo info : pm.getInstalledPackages(FcmDetector.PACKAGE_FLAGS)) {
                if (info.packageName != null && !Prefs.MODULE_PKG.equals(info.packageName)
                        && detector.containsFcm(info)) {
                    detected.add(info.packageName);
                }
            }
            synchronized (fcmScanRetryLock) {
                if (generation != fcmScannerGeneration) {
                    return false;
                }
                sDetectedFcmPackages = Collections.unmodifiableSet(detected);
                sFcmPackageCacheLoaded = true;
            }
            return true;
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Failed to refresh detected FCM packages", e);
            return false;
        }
    }

    private void hookActivityManagerService(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException, NoSuchFieldException {
        var ActivityManagerServiceClass = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        var mContextField = ActivityManagerServiceClass.getDeclaredField("mContext");
        mContextField.setAccessible(true);
        var IApplicationThreadClass = classLoader.loadClass("android.app.IApplicationThread");
        var IIntentReceiverClass = classLoader.loadClass("android.content.IIntentReceiver");
        var ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        var infoField = ProcessRecordClass.getDeclaredField("info");
        infoField.setAccessible(true);
        Method getRecordMethod;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12~16
            getRecordMethod = ActivityManagerServiceClass.getDeclaredMethod("getRecordForAppLOSP", IApplicationThreadClass);
        } else {
            // Android 8~11
            getRecordMethod = ActivityManagerServiceClass.getDeclaredMethod("getRecordForAppLocked", IApplicationThreadClass);
        }
        Method broadcastMethod;
        int intentArgIndex;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, String[] excludedPermissions,
            //    String[] excludedPackages, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, String[].class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, String[] excludedPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else {
            // int broadcastIntent(IApplicationThread caller,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 1;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntent",
                    IApplicationThreadClass,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        }
        hookE(broadcastMethod).intercept(chain -> {
            if (chain.getArg(intentArgIndex) instanceof Intent intent) {
                if (ACTION_REMOTE_INTENT.equals(intent.getAction())
                        && getInvoker(getRecordMethod).invoke(chain.getThisObject(), chain.getArg(0)) instanceof Object app
                        && infoField.get(app) instanceof ApplicationInfo info
                        && GMS_PACKAGE_NAME.equals(info.packageName)) {
                    // Wake / auto-start only apps allowed by the manual or automatic FCM policy.
                    if (intent.getPackage() instanceof String targetPackage
                            && shouldWake(targetPackage)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                && mContextField.get(chain.getThisObject()) instanceof Context mContext) {
                            getPowerExemptionManager(mContext).addToTemporaryAllowList(
                                    targetPackage, 102 /* PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA */,
                                    "GOOGLE_C2DM", 2000);
                        }
                        if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        }
                    }
                }
            }
            return chain.proceed();
        });
        deoptimize(broadcastMethod);
    }

    private void hookInternationalPolicyManager(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var InternationalPolicyManagerClass = classLoader.loadClass("com.miui.server.greeze.InternationalPolicyManager");
        var systemServerCl = InternationalPolicyManagerClass.getClassLoader();
        var isPushAppMethod = InternationalPolicyManagerClass.getDeclaredMethod("isPushApp", String.class);
        hookE(isPushAppMethod).intercept(chain -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
                var match = walker.walk(frames -> frames
                        .anyMatch(frame -> frame.getDeclaringClass() != null &&
                                frame.getDeclaringClass().getClassLoader() == systemServerCl &&
                                (frame.getMethodName().equals("isRestrictNet"))));
                if (match) return false;
            }
            return chain.proceed();
        });
    }

    private void hookProcessCleanerBase(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException {
        var ProcessCleanerBaseClass = classLoader.loadClass("com.android.server.am.ProcessCleanerBase");
        var ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        var mGetApplicationInfo = ProcessRecordClass.getDeclaredMethod("getApplicationInfo");
        var ProcessManagerServiceClass = classLoader.loadClass("com.android.server.am.ProcessManagerService");
        var mPkms = ProcessManagerServiceClass.getDeclaredField("mPkms");
        mPkms.setAccessible(true);
        // boolean isForceStopEnable(ProcessRecord app, int policy, ProcessManagerService pms)
        var isForceStopEnableMethod = ProcessCleanerBaseClass.getDeclaredMethod("isForceStopEnable", ProcessRecordClass, int.class, ProcessManagerServiceClass);
        hookE(isForceStopEnableMethod).intercept(chain -> {
            if (chain.getArg(1) instanceof Integer policy && policy != 13 && mPkms.get(chain.getArg(2)) instanceof PackageManager pm) {
                var info = (ApplicationInfo) getInvoker(mGetApplicationInfo).invoke(chain.getArg(0));
                var intent = new Intent(ACTION_REMOTE_INTENT);
                intent.setPackage(info.packageName);
                var isPushApp = !pm.queryBroadcastReceivers(intent, 0).isEmpty();
                if (isPushApp) return false;
            }
            return chain.proceed();
        });
    }
}
