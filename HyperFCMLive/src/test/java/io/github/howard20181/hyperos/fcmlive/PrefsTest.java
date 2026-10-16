package io.github.howard20181.hyperos.fcmlive;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.InOrder;

import java.util.Collections;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PrefsTest {
    private Context context;
    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;

    @Before
    public void setUp() {
        context = mock(Context.class);
        preferences = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putStringSet(eq(Prefs.KEY_ALLOWLIST), anySet())).thenReturn(editor);
        when(editor.putBoolean(Prefs.KEY_AUTO_FCM, true)).thenReturn(editor);
        when(editor.putBoolean(Prefs.KEY_AUTO_FCM, false)).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
    }

    @Test
    public void writesBothPolicyPartsBeforeBroadcasting() {
        Set<String> manual = Collections.singleton("com.example.manual");

        assertTrue(Prefs.writePolicy(context, preferences, manual, true));

        InOrder order = inOrder(preferences, editor, context);
        order.verify(preferences).edit();
        order.verify(editor).putStringSet(eq(Prefs.KEY_ALLOWLIST), eq(manual));
        order.verify(editor).putBoolean(Prefs.KEY_AUTO_FCM, true);
        order.verify(editor).commit();
        order.verify(context).sendBroadcast(org.mockito.ArgumentMatchers.argThat(
                intent -> Prefs.ACTION_ALLOWLIST_CHANGED.equals(intent.getAction())));
    }

    @Test
    public void legacyAllowlistWritePreservesAutomaticMode() {
        when(preferences.getBoolean(Prefs.KEY_AUTO_FCM, false)).thenReturn(true);

        assertTrue(Prefs.writeAllowlist(context, preferences,
                Collections.singleton("com.example.manual")));

        verify(editor).putBoolean(Prefs.KEY_AUTO_FCM, true);
    }

    @Test
    public void failedCommitDoesNotBroadcastANewPolicy() {
        when(editor.commit()).thenReturn(false);
        assertFalse(Prefs.writePolicy(context, preferences, Collections.emptySet(), false));
        verify(context, never()).sendBroadcast(any(android.content.Intent.class));
    }
}
