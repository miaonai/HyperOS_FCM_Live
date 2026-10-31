package io.github.howard20181.hyperos.fcmlive;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/** Run actual Hooker registration and callbacks against representative OEM class signatures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class HyperOsHookCompatibilityTest {
    private static final String GMS = "com.google.android.gms";
    private static final String PUSH = "com.google.android.c2dm.intent.RECEIVE";
    private static final String CONNECTED = "com.google.android.gcm.CONNECTED";
    private static final String NETD = "com.miui.powerkeeper.utils.NetdExecutor";
    private static final String OBSERVER = "com.miui.powerkeeper.utils.GmsObserver";
    private static final String GREEZE = "com.miui.server.greeze.GreezeManagerService";
    private static final String LISTS = "com.miui.server.greeze.power.ListAppsManager";
    private static final String POLICY = "com.android.server.am.ProcessPolicy";
    private Hooker module;
    private final Map<Executable, XposedInterface.Hooker> callbacks = new HashMap<>();

    @Before public void setUp() throws Exception {
        module = mock(Hooker.class);
        when(module.getApiVersion()).thenReturn(102);
        Field identities = Hooker.class.getDeclaredField("hookedIds");
        identities.setAccessible(true);
        identities.set(module, new HashSet<String>());
        when(module.hook(any(Executable.class))).thenAnswer(invocation -> {
            Executable target = invocation.getArgument(0);
            XposedInterface.HookBuilder builder = mock(XposedInterface.HookBuilder.class, RETURNS_SELF);
            when(builder.intercept(any())).thenAnswer(registration -> {
                callbacks.put(target, registration.getArgument(0));
                return mock(XposedInterface.HookHandle.class);
            });
            return builder;
        });
    }

    @Test public void powerKeeper4200RemovedNetdMethodDoesNotPreventTheReplacementHook() throws Throwable {
        install("hookGmsObserver", Map.of(NETD, EmptyNetd.class, OBSERVER, ModernObserver.class));
        assertForcedFalse(ModernObserver.class.getDeclaredMethod("updateFrameworkGmsNetStatus", boolean.class));
    }

    @Test public void absentNetdClassDoesNotPreventTheReplacementHook() throws Throwable {
        install("hookGmsObserver", Map.of(OBSERVER, ModernObserver.class));
        assertForcedFalse(ModernObserver.class.getDeclaredMethod("updateFrameworkGmsNetStatus", boolean.class));
    }

    @Test public void missingLegacyObserverMethodsDoNotPreventOtherPresentMethods() throws Throwable {
        install("hookGmsObserver", Map.of(NETD, LegacyNetd.class, OBSERVER, MixedObserver.class));
        assertForcedFalse(MixedObserver.class.getDeclaredMethod("updateGmsNetWork", boolean.class));
        assertForcedFalse(MixedObserver.class.getDeclaredMethod("updateFrameworkGmsNetStatus", boolean.class));
        Method netd = LegacyNetd.class.getDeclaredMethod("initGmsChain", String.class, int.class, String.class);
        XposedInterface.Chain call = call(netd, null, null, "chain", 42, "DROP");
        callback(netd).intercept(call);
        verify(call).proceed(new Object[]{"chain", 42, "ACCEPT"});
    }

    @Test public void legacyPowerKeeperHooksContinueToWork() throws Throwable {
        install("hookGmsObserver", Map.of(NETD, LegacyNetd.class, OBSERVER, LegacyObserver.class));
        for (String name : List.of("updateGmsAlarm", "updateGmsNetWork", "updateGoogleReletivesWakelock")) {
            assertForcedFalse(LegacyObserver.class.getDeclaredMethod(name, boolean.class));
        }
    }

    @Test public void missingGreezeUidLookupAndTriggerDoNotPreventBroadcastHooks() throws Throwable {
        install("hookGreezeManagerService", Map.of(GREEZE, ModernGreeze.class));
        Method broadcast = ModernGreeze.class.getDeclaredMethod("isAllowBroadcast",
                int.class, String.class, int.class, String.class, String.class);
        XposedInterface.Chain push = call(broadcast, null, false, 1000, GMS, 10001, "com.example.app", PUSH);
        assertEquals(true, callback(broadcast).intercept(push));
        verify(push, never()).proceed();
        XposedInterface.Chain connected = call(broadcast, null, false,
                1000, null, 10001, "com.google.android.gms.persistent", CONNECTED);
        assertEquals(true, callback(broadcast).intercept(connected));
        XposedInterface.Chain unrelated = call(broadcast, null, false,
                1000, "com.example.sender", 10001, "com.example.app", PUSH);
        assertEquals(false, callback(broadcast).intercept(unrelated));
        verify(unrelated).proceed();
        Method defer = ModernGreeze.class.getDeclaredMethod("deferBroadcastForMiui", String.class);
        assertEquals(false, callback(defer).intercept(call(defer, null, true, CONNECTED)));
        assertEquals(true, callback(defer).intercept(call(defer, null, true, "com.example.UNRELATED")));
    }

    @Test public void greezeTriggerBooleanOverloadKeepsOriginalArgumentsUntouched() throws Throwable {
        install("hookGreezeManagerService", Map.of(GREEZE, BooleanTrigger.class));
        assertForcedFalse(BooleanTrigger.class.getDeclaredMethod("triggerGMSLimitAction", boolean.class));
    }

    @Test public void greezeTriggerNoArgsDisablesTheLimitBeforeProceeding() throws Throwable {
        install("hookGreezeManagerService", Map.of(GREEZE, NoArgsTrigger.class));
        NoArgsTrigger receiver = new NoArgsTrigger();
        Method trigger = NoArgsTrigger.class.getDeclaredMethod("triggerGMSLimitAction");
        XposedInterface.Chain chain = call(trigger, receiver, null);
        callback(trigger).intercept(chain);
        assertFalse(receiver.mGmsLimitEnabled);
        verify(chain).proceed();
    }

    @Test public void domesticDeferralBypassIsRestrictedToFcmActions() throws Throwable {
        install("hookDomesticPolicyManager", Map.of("com.miui.server.greeze.DomesticPolicyManager", DomesticPolicy.class));
        Method defer = DomesticPolicy.class.getDeclaredMethod("deferBroadcast", String.class);
        for (String action : List.of(PUSH, CONNECTED)) {
            XposedInterface.Chain fcm = call(defer, null, true, action);
            assertEquals(false, callback(defer).intercept(fcm));
            verify(fcm, never()).proceed();
        }
        XposedInterface.Chain unrelated = call(defer, null, true, "com.example.UNRELATED");
        assertEquals(true, callback(defer).intercept(unrelated));
        verify(unrelated).proceed();
    }

    @Test public void processPolicyAcceptsImmutableListsAndPreservesOriginalEntries() throws Throwable {
        install("hookProcessPolicy", Map.of(POLICY, ProcessPolicy.class));
        Method getter = ProcessPolicy.class.getDeclaredMethod("getWhiteList", int.class);
        List<String> original = List.of("com.example.system", GMS);
        Object result = callback(getter).intercept(call(getter, null, original, 1));
        assertEquals(List.of("com.example.system", GMS, "com.google.android.gms.persistent"), result);
        assertEquals(List.of("com.example.system", GMS), original);
        assertSame(original, callback(getter).intercept(call(getter, null, original, 2)));
    }

    @Test public void listAppsManagerSupportsBothFieldNamesAndDeoptimizesTheWhitelist() throws Throwable {
        for (Class<?> shape : List.of(LegacyLists.class, ModernLists.class)) {
            callbacks.clear();
            install("hookListAppsManager", Map.of(LISTS, shape));
            Object receiver = shape.getDeclaredConstructor().newInstance();
            Executable constructor = shape.getDeclaredConstructor();
            callback(constructor).intercept(call(constructor, receiver, null));
            Field black = shape.getDeclaredField(shape == LegacyLists.class ? "mSystemBlackList" : "SYSTEM_BLACK_LIST");
            assertEquals(List.of("com.example.system"), black.get(receiver));
            Method whitelist = shape.getDeclaredMethod("isInWhiteList", String.class);
            callback(whitelist).intercept(call(whitelist, receiver, true, GMS));
            Field white = shape.getDeclaredField(shape == LegacyLists.class ? "mUseDataWhiteList" : "USE_DATA_WHITE_LIST");
            assertEquals(Set.of("com.example.system", GMS), white.get(receiver));
            verify(module).deoptimize(whitelist);
        }
    }

    private void assertForcedFalse(Method method) throws Throwable {
        XposedInterface.Chain chain = call(method, null, "original result", true);
        assertEquals("original result", callback(method).intercept(chain));
        verify(chain).proceed(new Object[]{false});
        assertEquals(List.of(true), chain.getArgs());
    }

    private XposedInterface.Hooker callback(Executable target) {
        assertTrue("Hook must register despite other missing OEM APIs: " + target, callbacks.containsKey(target));
        return callbacks.get(target);
    }

    private XposedInterface.Chain call(Executable target, Object receiver, Object result, Object... args) throws Throwable {
        XposedInterface.Chain chain = mock(XposedInterface.Chain.class);
        when(chain.getExecutable()).thenReturn(target);
        when(chain.getThisObject()).thenReturn(receiver);
        when(chain.getArgs()).thenReturn(Collections.unmodifiableList(Arrays.asList(args)));
        when(chain.getArg(anyInt())).thenAnswer(invocation -> args[invocation.getArgument(0, Integer.class)]);
        when(chain.proceed()).thenReturn(result);
        when(chain.proceed(any(Object[].class))).thenReturn(result);
        return chain;
    }

    private void install(String installer, Map<String, Class<?>> classes) throws Throwable {
        ClassLoader rom = new ClassLoader(getClass().getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (classes.containsKey(name)) return classes.get(name);
                if (name.startsWith("com.miui.") || name.startsWith("com.android.server.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }
        };
        Method install = Hooker.class.getDeclaredMethod(installer, ClassLoader.class);
        install.setAccessible(true);
        try {
            install.invoke(module, rom);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    public static class EmptyNetd {}
    public static class LegacyNetd { public void initGmsChain(String chain, int uid, String policy) {} }
    public static class ModernObserver { public void updateFrameworkGmsNetStatus(boolean restricted) {} }
    public static class MixedObserver {
        public void updateGmsNetWork(boolean restricted) {}
        public void updateFrameworkGmsNetStatus(boolean restricted) {}
    }
    public static class LegacyObserver {
        public void updateGmsAlarm(boolean restricted) {}
        public void updateGmsNetWork(boolean restricted) {}
        public void updateGoogleReletivesWakelock(boolean restricted) {}
    }
    public static class ModernGreeze {
        public boolean isAllowBroadcast(int callerUid, String caller, int calleeUid, String callee, String action) { return false; }
        public boolean deferBroadcastForMiui(String action) { return true; }
    }
    public static class BooleanTrigger { public void triggerGMSLimitAction(boolean enabled) {} }
    public static class NoArgsTrigger {
        public boolean mGmsLimitEnabled = true;
        public void triggerGMSLimitAction() {}
    }
    public static class DomesticPolicy { public boolean deferBroadcast(String action) { return true; } }
    public static class ProcessPolicy { public List<String> getWhiteList(int flags) { return List.of(); } }
    public static class LegacyLists {
        public List<String> mSystemBlackList = new ArrayList<>(List.of(GMS, "com.example.system"));
        public Set<String> mUseDataWhiteList = new HashSet<>(Set.of("com.example.system"));
        public boolean isInWhiteList(String pkg) { return mUseDataWhiteList.contains(pkg); }
    }
    public static class ModernLists {
        public static List<String> SYSTEM_BLACK_LIST = new ArrayList<>(List.of(GMS, "com.example.system"));
        public static Set<String> USE_DATA_WHITE_LIST = new HashSet<>(Set.of("com.example.system"));
        public boolean isInWhiteList(String pkg) { return USE_DATA_WHITE_LIST.contains(pkg); }
    }
}
