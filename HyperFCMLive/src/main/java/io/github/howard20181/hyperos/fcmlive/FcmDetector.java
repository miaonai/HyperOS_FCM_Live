package io.github.howard20181.hyperos.fcmlive;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import java.util.HashSet;
import java.util.Set;

/** Static manifest evidence only: this cannot verify registration or delivery. */
final class FcmDetector {
    static final int PACKAGE_FLAGS = PackageManager.GET_RECEIVERS
            | PackageManager.GET_SERVICES | PackageManager.MATCH_DISABLED_COMPONENTS;

    private final Set<String> messagingPackages = new HashSet<>();

    FcmDetector(PackageManager pm) {
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
        Intent receive = new Intent("com.google.android.c2dm.intent.RECEIVE")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        for (ResolveInfo info : pm.queryBroadcastReceivers(receive, flags)) {
            if (info.activityInfo != null) {
                messagingPackages.add(info.activityInfo.packageName);
            }
        }
        Intent messaging = new Intent("com.google.firebase.MESSAGING_EVENT")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        for (ResolveInfo info : pm.queryIntentServices(messaging, flags)) {
            if (info.serviceInfo != null) {
                messagingPackages.add(info.serviceInfo.packageName);
            }
        }
    }

    boolean containsFcm(PackageInfo info) {
        if (messagingPackages.contains(info.packageName)) {
            return true;
        }
        // Include disabled receivers/services. Do not count Analytics-only receivers.
        if (info.receivers != null) {
            for (ActivityInfo receiver : info.receivers) {
                if ("com.google.firebase.iid.FirebaseInstanceIdReceiver".equals(receiver.name)
                        || "com.google.android.gms.gcm.GcmReceiver".equals(receiver.name)) {
                    return true;
                }
            }
        }
        if (info.services != null) {
            for (ServiceInfo service : info.services) {
                if ("com.google.firebase.messaging.FirebaseMessagingService".equals(service.name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
