package io.github.howard20181.hyperos.fcmlive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class FcmDetectorTest {
    private static final String PACKAGE = "com.example.pushapp";

    private PackageManager packageManager;

    @Before
    public void setUp() {
        packageManager = mock(PackageManager.class);
        when(packageManager.queryBroadcastReceivers(any(Intent.class), anyInt()))
                .thenReturn(Collections.emptyList());
        when(packageManager.queryIntentServices(any(Intent.class), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    public void recognizesFirebaseInstanceIdReceiverFromPackageInfo() {
        PackageInfo app = appWithReceiver(PACKAGE,
                "com.google.firebase.iid.FirebaseInstanceIdReceiver", true);

        assertTrue(detector().containsFcm(app));
    }

    @Test
    public void recognizesGcmReceiverFromPackageInfo() {
        PackageInfo app = appWithReceiver(PACKAGE,
                "com.google.android.gms.gcm.GcmReceiver", true);

        assertTrue(detector().containsFcm(app));
    }

    @Test
    public void recognizesFirebaseMessagingServiceFromPackageInfo() {
        PackageInfo app = appWithService(PACKAGE,
                "com.google.firebase.messaging.FirebaseMessagingService", true);

        assertTrue(detector().containsFcm(app));
    }

    @Test
    public void recognizesCustomReceiverResolvedForC2dmReceiveAction() {
        when(packageManager.queryBroadcastReceivers(any(Intent.class), anyInt()))
                .thenReturn(Collections.singletonList(receiver(PACKAGE,
                        "com.example.pushapp.CustomPushReceiver", true)));
        FcmDetector detector = detector();

        assertTrue(detector.containsFcm(emptyApp(PACKAGE)));

        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(packageManager).queryBroadcastReceivers(intent.capture(),
                eq(PackageManager.MATCH_DISABLED_COMPONENTS));
        assertEquals("com.google.android.c2dm.intent.RECEIVE", intent.getValue().getAction());
        assertTrue("stopped packages must be included",
                (intent.getValue().getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0);
    }

    @Test
    public void recognizesCustomServiceResolvedForMessagingEventAction() {
        when(packageManager.queryIntentServices(any(Intent.class), anyInt()))
                .thenReturn(Collections.singletonList(service(PACKAGE,
                        "com.example.pushapp.CustomPushService", true)));
        FcmDetector detector = detector();

        assertTrue(detector.containsFcm(emptyApp(PACKAGE)));

        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(packageManager).queryIntentServices(intent.capture(),
                eq(PackageManager.MATCH_DISABLED_COMPONENTS));
        assertEquals("com.google.firebase.MESSAGING_EVENT", intent.getValue().getAction());
        assertTrue("stopped packages must be included",
                (intent.getValue().getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0);
    }

    @Test
    public void includesDisabledFirebaseReceiver() {
        PackageInfo app = emptyApp(PACKAGE);
        app.receivers = new ActivityInfo[] {activityInfo(
                "com.google.firebase.iid.FirebaseInstanceIdReceiver", false)};

        assertTrue(detector().containsFcm(app));
    }

    @Test
    public void includesDisabledMessagingServiceAndRequestsItsMetadata() {
        PackageInfo app = emptyApp(PACKAGE);
        app.services = new ServiceInfo[] {serviceInfo(
                "com.google.firebase.messaging.FirebaseMessagingService", false)};

        assertTrue(detector().containsFcm(app));
        assertTrue((FcmDetector.PACKAGE_FLAGS & PackageManager.GET_RECEIVERS) != 0);
        assertTrue((FcmDetector.PACKAGE_FLAGS & PackageManager.GET_SERVICES) != 0);
        assertTrue((FcmDetector.PACKAGE_FLAGS & PackageManager.MATCH_DISABLED_COMPONENTS) != 0);
    }

    @Test
    public void analyticsOnlyReceiverIsNotClassifiedAsFcm() {
        PackageInfo app = appWithReceiver(PACKAGE,
                "com.google.android.gms.measurement.AppMeasurementReceiver", true);

        assertFalse(detector().containsFcm(app));
    }

    @Test
    public void packageWithoutMessagingComponentsIsNotClassifiedAsFcm() {
        assertFalse(detector().containsFcm(emptyApp(PACKAGE)));
    }

    private FcmDetector detector() {
        return new FcmDetector(packageManager);
    }

    private static PackageInfo appWithReceiver(String packageName, String className,
                                               boolean enabled) {
        PackageInfo app = emptyApp(packageName);
        app.receivers = new ActivityInfo[] {activityInfo(className, enabled)};
        return app;
    }

    private static PackageInfo appWithService(String packageName, String className,
                                              boolean enabled) {
        PackageInfo app = emptyApp(packageName);
        app.services = new ServiceInfo[] {serviceInfo(className, enabled)};
        return app;
    }

    private static PackageInfo emptyApp(String packageName) {
        PackageInfo app = new PackageInfo();
        app.packageName = packageName;
        return app;
    }

    private static ResolveInfo receiver(String packageName, String className, boolean enabled) {
        ResolveInfo result = new ResolveInfo();
        result.activityInfo = activityInfo(className, enabled);
        result.activityInfo.packageName = packageName;
        return result;
    }

    private static ResolveInfo service(String packageName, String className, boolean enabled) {
        ResolveInfo result = new ResolveInfo();
        result.serviceInfo = serviceInfo(className, enabled);
        result.serviceInfo.packageName = packageName;
        return result;
    }

    private static ActivityInfo activityInfo(String className, boolean enabled) {
        ActivityInfo info = new ActivityInfo();
        info.name = className;
        info.enabled = enabled;
        return info;
    }

    private static ServiceInfo serviceInfo(String className, boolean enabled) {
        ServiceInfo info = new ServiceInfo();
        info.name = className;
        info.enabled = enabled;
        return info;
    }
}
