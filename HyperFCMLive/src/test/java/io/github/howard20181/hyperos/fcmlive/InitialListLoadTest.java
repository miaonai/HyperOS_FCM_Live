package io.github.howard20181.hyperos.fcmlive;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.database.DataSetObserver;
import android.os.Looper;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

/** Observe every published list while exercising the real package and policy loaders. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "zh-rCN-w412dp-h892dp-mdpi")
public class InitialListLoadTest {
    private static final String FIRST_BY_NAME = "com.example.alpha";
    private static final String SELECTED = "com.example.zulu";
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private SharedPreferences prefs;
    private XposedService service;
    private final List<String> publishedFirstPackages = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();

    @Before public void setUp() {
        installFcmApp(FIRST_BY_NAME, "Alpha");
        installFcmApp(SELECTED, "Zulu");
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("initial_list_policy", Context.MODE_PRIVATE);
        prefs.edit().clear().putStringSet(Prefs.KEY_ALLOWLIST, Set.of(SELECTED)).commit();
        service = mock(XposedService.class);
        when(service.getRemotePreferences(Prefs.GROUP_CONFIG)).thenReturn(prefs);
        controller = Robolectric.buildActivity(MainActivity.class);
        activity = controller.get();
    }

    @After public void tearDown() {
        releases.forEach(CountDownLatch::countDown);
        controller.pause().stop().destroy();
    }

    @Test public void packageScanBeforeServiceBindingNeverPublishesUncheckedOrder() throws Exception {
        start();
        drain("appLoader");
        assertFalse(field("loading").getBoolean(activity));
        assertTrue("The package scan must have produced actual apps", allApps().size() >= 2);
        assertEquals("No provisional alphabetical/FCM list may reach the adapter before policy arrives",
                0, list().getCount());
        assertEquals(View.GONE, activity.findViewById(R.id.loading).getVisibility());
        assertEquals(activity.getString(R.string.service_unavailable),
                ((android.widget.TextView) activity.findViewById(R.id.empty)).getText().toString());
        bindAndRead();
        drain("policyWriter");
        assertOnlyPublished(SELECTED);
    }

    @Test public void packageScanBeforeSlowPolicyKeepsSearchAndFiltersFromPublishingPartialState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = blockPolicy(entered);
        start();
        bindAndRead();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            drain("appLoader");
            SearchControl search = activity.findViewById(R.id.search_view);
            for (boolean enabled : new boolean[]{true, false}) {
                field("onlyFcm").setBoolean(activity, enabled);
                field("showSystemApps").setBoolean(activity, enabled);
                search.setQuery("Zulu", false);
                search.setQuery("", false);
                assertEquals("Filtering must not expose rows with unknown checked states", 0, list().getCount());
            }
        } finally { release.countDown(); }
        drain("policyWriter");
        assertOnlyPublished(SELECTED);
    }

    @Test public void policyBeforePackageScanAlsoPublishesOnlyTheFinalOrder() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        executor("appLoader").execute(() -> {
            entered.countDown();
            await(release);
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        start();
        try {
            bindAndRead();
            drain("policyWriter");
            assertTrue(field("policyReady").getBoolean(activity));
            assertTrue(field("loading").getBoolean(activity));
            assertEquals(0, list().getCount());
        } finally { release.countDown(); }
        drain("appLoader");
        assertOnlyPublished(SELECTED);
    }

    @Test public void returningToActivityWaitsForBothRefreshedInputs() throws Exception {
        start();
        bindAndRead();
        drain("appLoader");
        drain("policyWriter");
        assertOnlyPublished(SELECTED);
        controller.pause().stop();
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of(FIRST_BY_NAME)).commit();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = blockPolicy(entered);
        publishedFirstPackages.clear();
        controller.restart().start().resume();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            drain("appLoader");
            assertEquals("The previous verified list must stay hidden until both refreshed inputs arrive",
                    View.INVISIBLE, list().getVisibility());
            assertTrue("Do not publish a new order using the previous policy", publishedFirstPackages.isEmpty());
        } finally { release.countDown(); }
        drain("policyWriter");
        assertOnlyPublished(FIRST_BY_NAME);
    }

    @Test public void failedPolicyReadShowsNoUncheckedListAndCanRetry() throws Exception {
        start();
        when(service.getRemotePreferences(Prefs.GROUP_CONFIG)).thenThrow(new IllegalStateException("Disconnected"));
        bindAndRead();
        drain("appLoader");
        drain("policyWriter");
        assertFalse(field("policyReady").getBoolean(activity));
        assertEquals(0, list().getCount());
        assertEquals("Failure must not leave an endless spinner", View.GONE,
                activity.findViewById(R.id.loading).getVisibility());
        assertEquals(activity.getString(R.string.policy_read_failed),
                ((android.widget.TextView) activity.findViewById(R.id.empty)).getText().toString());
        assertTrue(publishedFirstPackages.isEmpty());
        doReturn(prefs).when(service).getRemotePreferences(Prefs.GROUP_CONFIG);
        bindAndRead();
        drain("policyWriter");
        assertOnlyPublished(SELECTED);
    }

    private void start() throws Exception {
        controller.create().start().resume().visible();
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override public void onChanged() {
                if (adapter.getCount() > 0) publishedFirstPackages.add(adapter.getItem(0).packageName);
            }
        });
    }

    private CountDownLatch blockPolicy(CountDownLatch entered) {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        when(service.getRemotePreferences(Prefs.GROUP_CONFIG)).thenAnswer(invocation -> {
            entered.countDown();
            await(release);
            return prefs;
        });
        return release;
    }

    private void bindAndRead() throws Exception {
        field("xposedService").set(activity, service);
        Method read = MainActivity.class.getDeclaredMethod("readPolicy");
        read.setAccessible(true);
        read.invoke(activity);
    }

    private void assertOnlyPublished(String expected) {
        assertEquals(View.VISIBLE, list().getVisibility());
        assertTrue("Both inputs must produce visible rows", list().getCount() >= 2);
        assertFalse("Observe at least one nonempty data-set notification", publishedFirstPackages.isEmpty());
        for (String firstPackage : publishedFirstPackages) assertEquals("Every published order must already be final",
                expected, firstPackage);
        assertEquals(expected, ((AppListAdapter) list().getAdapter()).getItem(0).packageName);
        assertTrue(((AppListAdapter) list().getAdapter()).getItem(0).checked);
    }

    private AnimatedAppList list() { return activity.findViewById(R.id.app_list); }

    @SuppressWarnings("unchecked") private List<AppListAdapter.AppEntry> allApps() throws Exception {
        return (List<AppListAdapter.AppEntry>) field("allApps").get(activity);
    }

    private Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private ExecutorService executor(String name) throws Exception {
        return (ExecutorService) field(name).get(activity);
    }

    private void drain(String executorName) throws Exception {
        executor(executorName).submit(() -> { }).get(5, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private void installFcmApp(String packageName, String label) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.nonLocalizedLabel = label;
        info.applicationInfo.enabled = true;
        ServiceInfo messaging = new ServiceInfo();
        messaging.packageName = packageName;
        messaging.applicationInfo = info.applicationInfo;
        messaging.name = "com.google.firebase.messaging.FirebaseMessagingService";
        messaging.enabled = true;
        info.services = new ServiceInfo[]{messaging};
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(info);
    }
}
