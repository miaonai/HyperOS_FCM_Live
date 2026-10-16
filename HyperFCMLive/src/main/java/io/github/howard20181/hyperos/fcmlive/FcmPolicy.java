package io.github.howard20181.hyperos.fcmlive;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Pure policy rules shared by the wake hook and settings UI. */
final class FcmPolicy {
    private FcmPolicy() {
    }

    /** The effective set is the manual choices plus detected FCM apps when enabled. */
    static Set<String> effectiveAllowlist(Set<String> manualAllowlist, boolean autoFcm,
                                          Set<String> detectedFcmPackages) {
        Set<String> effective = manualAllowlist != null
                ? new HashSet<>(manualAllowlist) : new HashSet<>();
        if (autoFcm && detectedFcmPackages != null) {
            effective.addAll(detectedFcmPackages);
        }
        return Collections.unmodifiableSet(effective);
    }

    /** An empty effective set means that no app is allowed. */
    static boolean shouldWake(String packageName, Set<String> manualAllowlist,
                              boolean autoFcm, Set<String> detectedFcmPackages) {
        if (packageName == null) {
            return false;
        }
        return manualAllowlist != null && manualAllowlist.contains(packageName)
                || autoFcm && detectedFcmPackages != null
                && detectedFcmPackages.contains(packageName);
    }
}
