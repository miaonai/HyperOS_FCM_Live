package io.github.howard20181.hyperos.fcmlive;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import android.widget.CompoundButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;


import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "zh-rCN-w412dp-h892dp-mdpi")
public class ManagementUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private SharedPreferences prefs;

    @Before
    public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        activity = controller.get();
        // Drain the package loader before installing a deterministic list fixture.
        for (int i = 0; i < 200 && (boolean) field("loading").get(activity); i++) {
            Thread.sleep(10);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        assertFalse("Package loading completed", (boolean) field("loading").get(activity));
        prefs = activity.getSharedPreferences("test_policy", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        XposedService service = mock(XposedService.class);
        when(service.getRemotePreferences(Prefs.GROUP_CONFIG)).thenReturn(prefs);
        field("xposedService").set(activity, service);
        List<AppListAdapter.AppEntry> apps = apps();
        apps.clear();
        apps.add(app("org.telegram.messenger", "Telegram", true, false));
        apps.add(app("com.example.mail", "邮件", true, false));
        apps.add(app("com.example.system", "系统推送服务", true, true));
        apps.add(app("com.example.notes", "笔记", false, false));
        invoke("readPolicy");
    }

    @After public void tearDown() { controller.pause().stop().destroy(); }

    @Test public void selectAllIncludesHiddenSystemAppsAndPreservesManualChoices() throws Exception {
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of("com.example.notes")).commit();
        invoke("readPolicy");
        ((SearchControl) activity.findViewById(R.id.search_view)).setQuery("Telegram", false);
        invoke("confirmSelectFcm");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals(Set.of("com.example.notes"), Prefs.readAllowlist(prefs));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        awaitWrites();
        assertEquals(Set.of("org.telegram.messenger", "com.example.mail", "com.example.system",
                "com.example.notes"), Prefs.readAllowlist(prefs));
    }

    @Test public void cancelClearPreservesBothManualAndAutomaticPolicy() throws Exception {
        seedPolicy();
        invoke("confirmClearAll");
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Set.of("com.example.notes"), Prefs.readAllowlist(prefs));
        assertTrue(Prefs.readAutoFcm(prefs));
    }

    @Test public void confirmClearResetsAllChoicesAndAutomaticPolicy() throws Exception {
        seedPolicy();
        ((SearchControl) activity.findViewById(R.id.search_view)).setQuery("Telegram", false);
        invoke("confirmClearAll");
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        awaitWrites();
        assertTrue(Prefs.readAllowlist(prefs).isEmpty());
        assertFalse(Prefs.readAutoFcm(prefs));
        for (AppListAdapter.AppEntry app : apps()) assertFalse(app.checked);
    }

    @Test public void disconnectedServiceRestoresSwitchState() throws Exception {
        field("xposedService").set(activity, null);
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        View row = adapter.getView(0, null, activity.findViewById(R.id.app_list));
        ((CompoundButton) row.findViewById(R.id.app_check)).performClick();
        assertFalse(apps().get(0).checked);
        assertTrue(Prefs.readAllowlist(prefs).isEmpty());
        assertEquals(activity.getString(R.string.service_unavailable), ShadowToast.getTextOfLatestToast());
    }

    @Test public void automaticPolicyLocksFcmSwitchButKeepsManualChoices() throws Exception {
        seedPolicy();
        assertTrue(apps().get(0).automaticallyAllowed);
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        View row = adapter.getView(0, null, activity.findViewById(R.id.app_list));
        CompoundButton toggle = row.findViewById(R.id.app_check);
        assertTrue(toggle.isChecked());
        assertFalse(toggle.isEnabled());
        prefs.edit().putBoolean(Prefs.KEY_AUTO_FCM, false).commit();
        invoke("readPolicy");
        assertFalse(apps().get(0).checked);
        assertTrue(apps().get(3).checked);
    }

    @Test public void searchExpandsAndBackRestoresCompactToolbar() throws Exception {
        SearchControl search = activity.findViewById(R.id.search_view);
        assertTrue(search.isIconified());
        search.setIconified(false);
        settleMotion();
        assertEquals(View.GONE, activity.findViewById(R.id.title).getVisibility());
        search.setQuery("Telegram", false);
        ((android.window.OnBackInvokedCallback) field("searchBackCallback").get(activity)).onBackInvoked();
        assertTrue(search.isIconified());
        assertEquals("", search.getQuery().toString());
        settleMotion();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.title).getVisibility());
    }

    @Test public void searchMorphKeepsHeaderHeightAndCanReverseMidFlight() throws Exception {
        View root = activity.findViewById(R.id.root);
        layoutForMotion(root);
        SearchControl search = activity.findViewById(R.id.search_view);
        View header = activity.findViewById(R.id.header);
        int height = header.getHeight();
        View magnifier = ((android.view.ViewGroup) search).getChildAt(1);
        float compactX = magnifier.getX();
        search.setIconified(false);
        assertEquals(compactX, magnifier.getX(), 0.01f);
        Field morphField = SearchControl.class.getDeclaredField("morph");
        morphField.setAccessible(true);
        android.animation.ValueAnimator opening = (android.animation.ValueAnimator) morphField.get(search);
        assertNotNull("Search should animate after layout", opening);
        opening.setCurrentPlayTime(100);
        float intermediateX = magnifier.getX();
        assertTrue("Icon should move between endpoints: " + intermediateX + " / " + compactX,
                intermediateX > 0 && intermediateX < compactX);
        search.setIconified(true);
        assertEquals("Reversing must start at the current icon position", intermediateX, magnifier.getX(), 0.01f);
        settleMotion();
        assertEquals(compactX, magnifier.getX(), 0.01f);
        search.setIconified(false);
        settleMotion();
        assertEquals(0f, magnifier.getX(), 0.01f);
        assertEquals(height, header.getHeight());
        android.widget.EditText input = (android.widget.EditText) ((android.view.ViewGroup) search).getChildAt(0);
        assertEquals(View.VISIBLE, input.getVisibility());
        assertEquals(View.VISIBLE, ((android.view.ViewGroup) search).getChildAt(2).getVisibility());
        assertEquals("搜索", input.getHint().toString());
        search.setQuery("mail", false);
        assertEquals(1, ((AppListAdapter) field("adapter").get(activity)).getCount());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void unmatchedSearchShowsEmptyStateAndClearingRestoresApps() {
        View root = activity.findViewById(R.id.root);
        layoutForMotion(root);
        SearchControl search = activity.findViewById(R.id.search_view);
        search.setIconified(false);
        settleMotion();
        AnimatedAppList list = activity.findViewById(R.id.app_list);
        assertTrue("Exercise the phone's touch-mode selection path", list.isInTouchMode());
        int originalCount = list.getCount();
        assertTrue(list.getChildCount() > 0);

        for (String query : new String[]{"不", "不存在", "不存在的应用"}) {
            search.setQuery(query, false);
            layoutForMotion(root);
            settleMotion();
            assertEquals(0, list.getCount());
            assertEquals(0, list.getChildCount());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.empty_state).getVisibility());
            assertEquals(activity.getString(R.string.no_apps),
                    ((android.widget.TextView) activity.findViewById(R.id.empty)).getText().toString());
        }

        search.setQuery("Telegram", false);
        layoutForMotion(root);
        settleMotion();
        assertEquals(1, list.getCount());
        assertEquals("org.telegram.messenger", AppListAdapter.boundPackage(list.getChildAt(0)));
        assertEquals(View.GONE, activity.findViewById(R.id.empty_state).getVisibility());

        search.setQuery("不存在", false);
        layoutForMotion(root);
        search.setQuery("", false);
        layoutForMotion(root);
        settleMotion();
        assertEquals(originalCount, list.getCount());
        assertEquals(View.GONE, activity.findViewById(R.id.empty_state).getVisibility());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void unmatchedSearchDuringPlacementCanRecoverBeforeNextLayout() throws Exception {
        View root = activity.findViewById(R.id.root);
        layoutForMotion(root);
        AnimatedAppList list = activity.findViewById(R.id.app_list);
        assertTrue(list.isInTouchMode());
        list.getChildAt(2).findViewById(R.id.app_check).performClick();
        layoutForMotion(root);
        assertTrue(list.getChildAt(0).getTranslationY() > 0);

        SearchControl search = activity.findViewById(R.id.search_view);
        search.setQuery("不存在", false);
        assertEquals(0, list.getCount());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.empty_state).getVisibility());
        // A second keystroke can arrive while the old row views are still attached.
        search.setQuery("不存在的应用", false);
        search.setQuery("", false);
        layoutForMotion(root);
        settleMotion();
        assertEquals(3, list.getCount());
        assertEquals("com.example.notes", AppListAdapter.boundPackage(list.getChildAt(0)));
        for (int i = 0; i < list.getChildCount(); i++) {
            assertEquals(0f, list.getChildAt(i).getTranslationY(), 0.01f);
        }
        awaitWrites();
    }

    @Test public void confirmationActionsAreEqualWidthAndTitleCentered() throws Exception {
        invoke("confirmClearAll");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        settleMotion();
        View panel = dialog.getWindow().getDecorView();
        panel.measure(View.MeasureSpec.makeMeasureSpec(388, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, 388, panel.getMeasuredHeight());
        android.widget.Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        android.widget.Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        assertEquals(cancel.getWidth(), confirm.getWidth());
        assertTrue(cancel.getWidth() > 130);
        assertTrue(cancel.getHeight() >= 48);
        android.graphics.Rect visibleButton = new android.graphics.Rect();
        assertTrue(cancel.getGlobalVisibleRect(visibleButton));
        assertEquals("The button's rounded background must not be clipped", cancel.getHeight(), visibleButton.height());
        assertTrue(cancel.getLeft() < confirm.getLeft());
        assertTrue(confirm.getBackground() instanceof android.graphics.drawable.RippleDrawable);
        int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
        android.widget.TextView title = dialog.findViewById(titleId);
        assertEquals(android.view.Gravity.CENTER, title.getGravity());
        assertEquals(View.TEXT_ALIGNMENT_CENTER, title.getTextAlignment());
        View titleParent = (View) title.getParent();
        assertEquals(titleParent.getWidth() - titleParent.getPaddingLeft() - titleParent.getPaddingRight(),
                title.getWidth());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderSearchAndConfirmation() throws Exception {
        View root = activity.findViewById(R.id.root);
        layoutForMotion(root);
        SearchControl search = activity.findViewById(R.id.search_view);
        search.setIconified(false);
        settleMotion();
        saveRender(root, 412, 892, "management-search.png");
        invoke("confirmClearAll");
        settleMotion();
        View panel = ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
        panel.measure(View.MeasureSpec.makeMeasureSpec(388, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST));
        saveRender(panel, 388, panel.getMeasuredHeight(), "management-confirm.png");
    }

    @Test public void enabledAppsComeFirstAfterLoadToggleAndAutomaticPolicyChanges() throws Exception {
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of("com.example.notes")).commit();
        invoke("readPolicy");
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        assertEquals("com.example.notes", adapter.getItem(0).packageName);
        View row = adapter.getView(2, null, activity.findViewById(R.id.app_list));
        assertEquals("com.example.mail", adapter.getItem(2).packageName);
        row.findViewById(R.id.app_check).performClick();
        assertEquals("com.example.mail", adapter.getItem(0).packageName);
        assertEquals("com.example.notes", adapter.getItem(1).packageName);
        assertFalse(adapter.getItem(2).checked);
        awaitWrites();
        assertTrue(Prefs.readAllowlist(prefs).contains("com.example.mail"));
        prefs.edit().putBoolean(Prefs.KEY_AUTO_FCM, true).commit();
        invoke("readPolicy");
        assertEquals("org.telegram.messenger", adapter.getItem(0).packageName);
        assertTrue(adapter.getItem(0).automaticallyAllowed);
        invoke("confirmClearAll");
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        awaitWrites();
        assertEquals("org.telegram.messenger", adapter.getItem(0).packageName);
        for (int i = 0; i < adapter.getCount(); i++) assertFalse(adapter.getItem(i).checked);
    }

    @Test public void applicationIdentitySurvivesSorting() throws Exception {
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        long notesId = adapter.getItemId(2);
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of("com.example.notes")).commit();
        invoke("readPolicy");
        assertEquals("com.example.notes", adapter.getItem(0).packageName);
        assertEquals(notesId, adapter.getItemId(0));
        assertTrue(adapter.hasStableIds());
    }

    @Test public void restoredPolicyDoesNotAnimateEnabledAppsFromBelow() throws Exception {
        View root = activity.findViewById(R.id.root);
        layoutForMotion(root);
        settleMotion();
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of("com.example.mail")).commit();
        Method read = MainActivity.class.getDeclaredMethod("readPolicy");
        read.setAccessible(true);
        read.invoke(activity);
        assertPolicyFrame(() -> {
            layoutForMotion(root);
            AnimatedAppList list = activity.findViewById(R.id.app_list);
            assertEquals("com.example.mail", AppListAdapter.boundPackage(list.getChildAt(0)));
            assertEquals("Restoring saved selections must place enabled apps at the top immediately",
                    0f, list.getChildAt(0).getTranslationY(), 0.01f);
        });
    }

    @Test public void delayedPolicyAfterChangingBothFiltersDoesNotAnimateEnabledAppsFromBelow() throws Exception {
        View root = activity.findViewById(R.id.root);
        layoutForMotion(root);
        settleMotion();
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST,
                Set.of("com.example.mail", "com.example.system")).commit();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        XposedService service = mock(XposedService.class);
        when(service.getRemotePreferences(Prefs.GROUP_CONFIG)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return prefs;
        });
        field("xposedService").set(activity, service);
        Method read = MainActivity.class.getDeclaredMethod("readPolicy");
        read.setAccessible(true);
        read.invoke(activity);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (boolean[] filters : new boolean[][]{
                    {true, false}, {false, false}, {false, true}, {false, false}, {true, true}}) {
                field("onlyFcm").setBoolean(activity, filters[0]);
                field("showSystemApps").setBoolean(activity, filters[1]);
                invoke("filterApps");
                layoutForMotion(root);
                settleMotion();
            }
        } finally {
            release.countDown();
        }
        assertPolicyFrame(() -> {
            layoutForMotion(root);
            AnimatedAppList list = activity.findViewById(R.id.app_list);
            assertEquals("com.example.mail", AppListAdapter.boundPackage(list.getChildAt(0)));
            assertEquals("com.example.system", AppListAdapter.boundPackage(list.getChildAt(1)));
            for (int i = 0; i < list.getChildCount(); i++) {
                assertEquals("A late policy response must not replay switch placement animations",
                        0f, list.getChildAt(i).getTranslationY(), 0.01f);
            }
        });
    }

    private void assertPolicyFrame(Runnable assertion) throws Exception {
        // Queue the assertion after the real policy callback, before the next animation frame.
        // awaitWrites() drains UI work first and can hide the very first placement frame.
        ((ExecutorService) field("policyWriter").get(activity)).submit(() -> { }).get(5, TimeUnit.SECONDS);
        new android.os.Handler(Looper.getMainLooper()).post(assertion);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void aboutWindowHasNoDimOrBackgroundBlur() throws Exception {
        invoke("showAbout");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        android.view.WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
        assertEquals(0, attributes.flags & android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        assertEquals(0f, attributes.dimAmount, 0f);
        assertEquals(0, attributes.getBlurBehindRadius());
    }

    @Test public void reorderedRowMovesFromItsPreviousPositionAndCanBeFilteredMidFlight() throws Exception {
        View root = activity.findViewById(R.id.root);
        layoutForMotion(root);
        AnimatedAppList list = activity.findViewById(R.id.app_list);
        float oldTop = list.getChildAt(2).getTop();
        // Exercise the actual tap, without draining animation frames via the asynchronous read helper.
        list.getChildAt(2).findViewById(R.id.app_check).performClick();
        layoutForMotion(root);
        View notes = list.getChildAt(0);
        assertEquals("com.example.notes", AppListAdapter.boundPackage(notes));
        assertTrue("Moved row must start at its old position: oldTop=" + oldTop
                + ", newTop=" + notes.getTop(), notes.getTranslationY() > 0);
        assertEquals(oldTop, notes.getTop() + notes.getTranslationY(), 1f);
        ((SearchControl) activity.findViewById(R.id.search_view)).setQuery("笔记", false);
        layoutForMotion(root);
        assertEquals(1, list.getCount());
        assertEquals("com.example.notes", AppListAdapter.boundPackage(list.getChildAt(0)));
        assertEquals(oldTop, list.getChildAt(0).getTop() + list.getChildAt(0).getTranslationY(), 1f);
        settleMotion();
        assertEquals(0f, list.getChildAt(0).getTranslationY(), 0.01f);
        awaitWrites();
    }

    private void layoutForMotion(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 412, 892);
        root.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test public void newlyRevealedAppsStayBelowExistingRowsDuringPlacement() throws Exception {
        apps().add(1, apps().remove(3));
        field("onlyFcm").setBoolean(activity, true);
        invoke("filterApps");
        View root = activity.findViewById(R.id.root);
        layoutForMotion(root);
        AnimatedAppList list = activity.findViewById(R.id.app_list);
        field("onlyFcm").setBoolean(activity, false);
        invoke("filterApps");
        layoutForMotion(root);
        // Notes is inserted before Mail; Mail must cover it until it moves down.
        int notesDraw = -1, mailDraw = -1;
        for (int draw = 0; draw < list.getChildCount(); draw++) {
            String pkg = AppListAdapter.boundPackage(list.getChildAt(
                    list.getChildDrawingOrder(list.getChildCount(), draw)));
            if ("com.example.notes".equals(pkg)) notesDraw = draw;
            if ("com.example.mail".equals(pkg)) mailDraw = draw;
        }
        assertTrue("New Notes must draw before existing Mail", notesDraw < mailDraw);
    }

    @Test public void managementListHasNoScrollbarButKeepsEdgeFeedbackForShortLists() {
        assertFalse(activity.findViewById(R.id.app_list).isVerticalScrollBarEnabled());
        assertEquals(View.OVER_SCROLL_ALWAYS, activity.findViewById(R.id.app_list).getOverScrollMode());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void headerAndListShareOneContentRecordingPerFrame() {
        FrostedLayout root = new FrostedLayout(activity, null);
        int[] recordings = {0};
        View source = new View(activity) {
            @Override public void draw(Canvas canvas) { recordings[0]++; super.draw(canvas); }
        };
        source.setId(R.id.app_list);
        root.addView(source, new android.widget.FrameLayout.LayoutParams(-1, -1));
        View header = new View(activity);
        header.setId(R.id.header);
        root.addView(header, new android.widget.FrameLayout.LayoutParams(-1, 88));
        layoutForMotion(root);
        android.graphics.RenderNode frame = new android.graphics.RenderNode("header regression");
        frame.setPosition(0, 0, 412, 892);
        Canvas canvas = frame.beginRecording(412, 892);
        root.draw(canvas);
        frame.endRecording();
        assertEquals("One list recording feeds both sharp content and header blur", 1, recordings[0]);
        frame.discardDisplayList();
    }

    @Test public void reusedRowsSnapToTheirOwnSwitchState() throws Exception {
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of("org.telegram.messenger")).commit();
        invoke("readPolicy");
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        View row = adapter.getView(0, null, activity.findViewById(R.id.app_list));
        assertTrue(((CompoundButton) row.findViewById(R.id.app_check)).isChecked());
        row = adapter.getView(1, row, activity.findViewById(R.id.app_list));
        assertFalse(((CompoundButton) row.findViewById(R.id.app_check)).isChecked());
        Field position = CapsuleSwitch.class.getDeclaredField("position");
        position.setAccessible(true);
        assertEquals(0f, position.getFloat(row.findViewById(R.id.app_check)), 0f);
        row = adapter.getView(0, row, activity.findViewById(R.id.app_list));
        assertEquals(1f, position.getFloat(row.findViewById(R.id.app_check)), 0f);
    }

    @Test public void menuFiltersPersistAndDoNotChangePolicy() throws Exception {
        activity.findViewById(R.id.more).performClick();
        View menu = Shadows.shadowOf(activity.getApplication()).getLatestPopupWindow().getContentView();
        assertTrue("Menu should wrap content instead of filling 344dp",
                Shadows.shadowOf(activity.getApplication()).getLatestPopupWindow().getWidth() < 260);
        int[] ids = { R.id.menu_fcm_only, R.id.menu_system_apps, R.id.menu_clear_all,
                R.id.menu_select_fcm, R.id.menu_auto_fcm, R.id.menu_diagnostics, R.id.menu_about };
        for (int id : ids) assertNotNull(menu.findViewById(id));
        assertTrue(menu.findViewById(R.id.menu_fcm_only).createAccessibilityNodeInfo().isCheckable());
        assertFalse(menu.findViewById(R.id.menu_fcm_only).createAccessibilityNodeInfo().isChecked());
        menu.findViewById(R.id.menu_fcm_only).performClick();
        settleMotion();
        assertTrue(activity.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
                .getBoolean("only_fcm", false));
        assertEquals(2, ((AppListAdapter) field("adapter").get(activity)).getCount());
        assertTrue(Prefs.readAllowlist(prefs).isEmpty());
        activity.findViewById(R.id.more).performClick();
        assertTrue(Shadows.shadowOf(activity.getApplication()).getLatestPopupWindow().getContentView()
                .findViewById(R.id.menu_fcm_only).createAccessibilityNodeInfo().isChecked());
        Shadows.shadowOf(activity.getApplication()).getLatestPopupWindow().getContentView()
                .findViewById(R.id.menu_system_apps).performClick();
        settleMotion();
        assertEquals(3, ((AppListAdapter) field("adapter").get(activity)).getCount());
    }

    @Test public void immersiveInsetsKeepGestureAndKeyboardSpaceInsideTheList() {
        View root = activity.findViewById(R.id.root);
        View list = activity.findViewById(R.id.app_list);
        android.view.WindowInsets bars = new android.view.WindowInsets.Builder()
                .setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, 24, 0, 32))
                .build();
        root.dispatchApplyWindowInsets(bars);
        assertEquals(0, root.getPaddingBottom());
        assertEquals(24, activity.findViewById(R.id.header).getPaddingTop());
        assertEquals(52, list.getPaddingBottom());
        root.dispatchApplyWindowInsets(new android.view.WindowInsets.Builder(bars)
                .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 240))
                .build());
        assertEquals(260, list.getPaddingBottom());
        root.dispatchApplyWindowInsets(bars);
        assertEquals(52, list.getPaddingBottom());
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderManagementScreen() throws Exception {
        // An actual Android view render, retained as a visual review artifact.
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of("org.telegram.messenger")).commit();
        invoke("readPolicy");
        render("management-light.png");
        activity.findViewById(R.id.more).performClick();
        Shadows.shadowOf(activity.getApplication()).getLatestPopupWindow().getContentView()
                .findViewById(R.id.menu_fcm_only).performClick();
        settleMotion();
        activity.findViewById(R.id.more).performClick();
        settleMotion();
        android.widget.PopupWindow popup = Shadows.shadowOf(activity.getApplication()).getLatestPopupWindow();
        View menu = popup.getContentView().getRootView();
        int width = popup.getWidth();
        menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        saveRender(menu, width, menu.getMeasuredHeight(), "management-menu.png");
    }

    @Test
    @Config(sdk = 35, qualifiers = "zh-rCN-w412dp-h892dp-night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderDarkManagementScreen() throws Exception {
        seedPolicy();
        render("management-dark.png");
    }

    private void render(String filename) throws Exception {
        View root = activity.findViewById(R.id.root);
        root.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 412, 892);
        root.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 412, 892);
        android.widget.ListView list = activity.findViewById(R.id.app_list);
        for (int i = 0; i < list.getChildCount(); i++) {
            list.getChildAt(i).findViewById(R.id.app_check).jumpDrawablesToCurrentState();
        }
        FcmLabelView label = list.getChildAt(0).findViewById(R.id.app_label);
        assertTrue("FCM name must have a visible line", label.getHeight() >= label.getTextSize());
        assertTrue(label.getText().toString().contains("Telegram"));
        saveRender(root, 412, 892, filename);
    }

    private void saveRender(View view, int width, int height, String filename) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File directory = new File("build/reports/ui");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    private void seedPolicy() throws Exception {
        prefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of("com.example.notes"))
                .putBoolean(Prefs.KEY_AUTO_FCM, true).commit();
        invoke("readPolicy");
    }

    private AppListAdapter.AppEntry app(String pkg, String label, boolean fcm, boolean system) {
        AppListAdapter.AppEntry entry = new AppListAdapter.AppEntry(pkg, label, fcm);
        entry.systemApp = system;
        entry.icon = activity.getDrawable(android.R.drawable.sym_def_app_icon);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private List<AppListAdapter.AppEntry> apps() throws Exception {
        return (List<AppListAdapter.AppEntry>) field("allApps").get(activity);
    }

    private Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void invoke(String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
        if (name.equals("readPolicy")) awaitWrites();
    }

    private void awaitWrites() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ((ExecutorService) field("policyWriter").get(activity)).submit(() -> { }).get(5, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void settleMotion() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
    }

    @Test public void slowFailedWriteDoesNotBlockSelectionAndRestoresConfirmedPolicy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SharedPreferences blocked = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(blocked.edit()).thenReturn(editor);
        when(editor.commit()).thenAnswer(invocation -> {
            assertNotSame(Looper.getMainLooper().getThread(), Thread.currentThread());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return false;
        });
        when(((XposedService) field("xposedService").get(activity))
                .getRemotePreferences(Prefs.GROUP_CONFIG)).thenReturn(blocked);
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        View row = adapter.getView(2, null, activity.findViewById(R.id.app_list));
        try {
            row.findViewById(R.id.app_check).performClick();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("com.example.notes", adapter.getItem(0).packageName);
            assertTrue(adapter.getItem(0).checked);
            // A service rebind during the pending save must retry its deferred reload afterwards.
            field("policyReady").setBoolean(activity, false);
            Method read = MainActivity.class.getDeclaredMethod("readPolicy");
            read.setAccessible(true);
            read.invoke(activity);
        } finally { release.countDown(); }
        awaitWrites();
        awaitWrites();
        assertTrue(field("policyReady").getBoolean(activity));
        assertEquals("org.telegram.messenger", adapter.getItem(0).packageName);
        for (AppListAdapter.AppEntry app : apps()) assertFalse(app.checked);
        assertEquals(activity.getString(R.string.save_failed), ShadowToast.getTextOfLatestToast());
    }

    @Test public void rapidSelectionsPersistInOrderWithoutLosingEarlierChoices() throws Exception {
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        View notes = adapter.getView(2, null, activity.findViewById(R.id.app_list));
        notes.findViewById(R.id.app_check).performClick();
        View telegram = adapter.getView(1, null, activity.findViewById(R.id.app_list));
        telegram.findViewById(R.id.app_check).performClick();
        telegram.findViewById(R.id.app_check).performClick();
        awaitWrites();
        assertEquals(Set.of("com.example.notes"), Prefs.readAllowlist(prefs));
        assertEquals("com.example.notes", adapter.getItem(0).packageName);
    }

    @Test public void recreatedActivityReadsAfterAnOlderPendingSave() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SharedPreferences savedPrefs = prefs;
        SharedPreferences blocked = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(blocked.edit()).thenReturn(editor);
        when(editor.commit()).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return savedPrefs.edit().putStringSet(Prefs.KEY_ALLOWLIST, Set.of("com.example.notes")).commit();
        });
        when(((XposedService) field("xposedService").get(activity))
                .getRemotePreferences(Prefs.GROUP_CONFIG)).thenReturn(blocked);
        AppListAdapter adapter = (AppListAdapter) field("adapter").get(activity);
        try {
            adapter.getView(2, null, activity.findViewById(R.id.app_list))
                    .findViewById(R.id.app_check).performClick();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            controller.pause().stop().destroy();
            controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
            activity = controller.get();
            XposedService service = mock(XposedService.class);
            when(service.getRemotePreferences(Prefs.GROUP_CONFIG)).thenReturn(savedPrefs);
            field("xposedService").set(activity, service);
            Method read = MainActivity.class.getDeclaredMethod("readPolicy");
            read.setAccessible(true);
            read.invoke(activity);
            assertFalse(field("policyReady").getBoolean(activity));
        } finally { release.countDown(); }
        awaitWrites();
        assertEquals(Set.of("com.example.notes"), field("allowlist").get(activity));
        assertTrue(field("policyReady").getBoolean(activity));
    }

    @Test public void menuUsesOneAnimationAndDoesNotScrollItsAnchor() {
        int before = activity.findViewById(R.id.header).getTop();
        activity.findViewById(R.id.more).performClick();
        android.widget.PopupWindow popup = Shadows.shadowOf(activity.getApplication()).getLatestPopupWindow();
        assertFalse(popup.isAboveAnchor());
        assertEquals(0, popup.getAnimationStyle());
        assertNull(popup.getEnterTransition());
        assertNull(popup.getExitTransition());
        settleMotion();
        assertEquals(before, activity.findViewById(R.id.header).getTop());
        popup.dismiss();
        assertTrue("Window must stay attached during exit", popup.isShowing());
        settleMotion();
        assertFalse(popup.isShowing());
    }

    @Test public void aboutSheetStaysAttachedDuringDragDismissal() throws Exception {
        invoke("showAbout");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        View panel = dialog.getWindow().getDecorView();
        panel.measure(View.MeasureSpec.makeMeasureSpec(388, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, 388, panel.getMeasuredHeight());
        panel.getViewTreeObserver().dispatchOnPreDraw();
        settleMotion();
        assertEquals(0f, panel.getTranslationY(), 0.01f);
        long now = android.os.SystemClock.uptimeMillis();
        dispatchDrag(dialog, now, now, android.view.MotionEvent.ACTION_DOWN, 30);
        dispatchDrag(dialog, now, now + 40, android.view.MotionEvent.ACTION_MOVE, 150);
        assertTrue(panel.getTranslationY() > 0);
        dispatchDrag(dialog, now, now + 80, android.view.MotionEvent.ACTION_UP, 150);
        assertTrue(dialog.isShowing());
        settleMotion();
        assertFalse(dialog.isShowing());
    }

    @Test public void scrolledMessageDoesNotBlockDraggingTheSheetTitle() throws Exception {
        invoke("showAbout");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.setMessage(activity.getString(R.string.about_message, "test").repeat(10));
        // Constrain the window itself, so a scheduled traversal cannot undo the test's short viewport.
        dialog.getWindow().setLayout(388, 220);
        View panel = dialog.getWindow().getDecorView();
        panel.measure(View.MeasureSpec.makeMeasureSpec(388, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY));
        panel.layout(0, 0, 388, 220);
        panel.getViewTreeObserver().dispatchOnPreDraw();
        settleMotion();
        android.widget.ScrollView scroll = findScroll(panel);
        assertNotNull(scroll);
        scroll.scrollTo(0, 200);
        assertTrue(scroll.canScrollVertically(-1));
        long now = android.os.SystemClock.uptimeMillis();
        int[] scrollLocation = new int[2];
        int[] panelLocation = new int[2];
        scroll.getLocationOnScreen(scrollLocation);
        panel.getLocationOnScreen(panelLocation);
        float messageY = scrollLocation[1] - panelLocation[1] + 12;
        dispatchDrag(dialog, now, now, android.view.MotionEvent.ACTION_DOWN, messageY);
        dispatchDrag(dialog, now, now + 20, android.view.MotionEvent.ACTION_MOVE, messageY + 30);
        assertEquals("Dragging scrolled content must not move the sheet", 0f, panel.getTranslationY(), 0.01f);
        dispatchDrag(dialog, now, now + 40, android.view.MotionEvent.ACTION_UP, messageY + 30);
        now += 80;
        dispatchDrag(dialog, now, now, android.view.MotionEvent.ACTION_DOWN, 20);
        dispatchDrag(dialog, now, now + 40, android.view.MotionEvent.ACTION_MOVE, 140);
        assertTrue("Title drag should move the sheet even when its message is scrolled", panel.getTranslationY() > 0);
        dispatchDrag(dialog, now, now + 80, android.view.MotionEvent.ACTION_UP, 140);
        settleMotion();
        assertFalse(dialog.isShowing());
    }

    private android.widget.ScrollView findScroll(View view) {
        if (view instanceof android.widget.ScrollView scroll) return scroll;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ScrollView scroll = findScroll(group.getChildAt(i));
                if (scroll != null) return scroll;
            }
        }
        return null;
    }

    private void dispatchDrag(AlertDialog dialog, long down, long time, int action, float y) {
        View panel = dialog.getWindow().getDecorView();
        int[] location = new int[2];
        panel.getLocationOnScreen(location);
        float windowY = location[1] - panel.getTranslationY();
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, time, action,
                location[0] + 40, windowY + y, 0);
        event.offsetLocation(-location[0], -windowY);
        dialog.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderAboutSheet() throws Exception {
        invoke("showAbout");
        View panel = ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
        panel.measure(View.MeasureSpec.makeMeasureSpec(388, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(760, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, 388, panel.getMeasuredHeight());
        panel.getViewTreeObserver().dispatchOnPreDraw();
        settleMotion();
        saveRender(panel, 388, panel.getMeasuredHeight(), "management-about.png");
    }
}
