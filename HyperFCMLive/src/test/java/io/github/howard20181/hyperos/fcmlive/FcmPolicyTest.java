package io.github.howard20181.hyperos.fcmlive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public class FcmPolicyTest {
    @Test
    public void emptyPolicyAllowsNoApps() {
        assertFalse(FcmPolicy.shouldWake("com.example.app", Collections.emptySet(),
                false, Collections.emptySet()));
    }

    @Test
    public void manualChoicesRemainAllowedWhenAutomaticModeIsOff() {
        Set<String> manual = Collections.singleton("com.example.manual");

        assertTrue(FcmPolicy.shouldWake("com.example.manual", manual, false,
                Collections.singleton("com.example.detected")));
        assertFalse(FcmPolicy.shouldWake("com.example.detected", manual, false,
                Collections.singleton("com.example.detected")));
    }

    @Test
    public void automaticModeUnionsDetectedAppsWithManualChoices() {
        Set<String> manual = Collections.singleton("com.example.manual");
        Set<String> detected = Collections.singleton("com.example.detected");

        assertTrue(FcmPolicy.shouldWake("com.example.manual", manual, true, detected));
        assertTrue(FcmPolicy.shouldWake("com.example.detected", manual, true, detected));
        assertFalse(FcmPolicy.shouldWake("com.example.other", manual, true, detected));
        assertEquals(Set.of("com.example.manual", "com.example.detected"),
                FcmPolicy.effectiveAllowlist(manual, true, detected));
    }
}
