package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** FCM wake policy for China HyperOS. */
public class MainActivity extends Activity implements SearchView.OnQueryTextListener {
    private static final String UI_SETTINGS = "ui_settings";
    private static final String SHOW_SYSTEM_APPS = "show_system_apps";
    private static final String ONLY_FCM = "only_fcm";
    private final List<AppListAdapter.AppEntry> allApps = new ArrayList<>();
    private final List<AppListAdapter.AppEntry> filteredApps = new ArrayList<>();
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();
    // Shared across activity recreation: a new page reads only after older accepted writes finish.
    private static final ExecutorService policyWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fcm-policy");
        thread.setDaemon(true);
        return thread;
    });
    private Set<String> allowlist = new HashSet<>();
    private Set<String> confirmedAllowlist = new HashSet<>();
    private boolean confirmedAutoFcm;
    private long policyRevision;
    private int pendingWrites;
    private boolean policyReady;
    private boolean policyLoading;
    private boolean policyReadFailed;
    private long policyReadRevision;
    private AppListAdapter adapter;
    private SearchControl searchView;
    private SharedPreferences uiSettings;
    private boolean showSystemApps;
    private boolean onlyFcm;
    private boolean autoFcm;
    private boolean loading = true;
    private boolean loadFailed;
    private int bottomInset;
    private XposedService xposedService;
    private ManagementMenu menu;
    private ManagementSheet sheet;
    private OnBackInvokedCallback searchBackCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(R.layout.activity_main);
        uiSettings = getSharedPreferences(UI_SETTINGS, MODE_PRIVATE);
        showSystemApps = uiSettings.getBoolean(SHOW_SYSTEM_APPS, false);
        onlyFcm = uiSettings.getBoolean(ONLY_FCM, false);
        searchView = findViewById(R.id.search_view);
        adapter = new AppListAdapter(this, filteredApps, (pkg, checked) -> {
            Set<String> updated = new HashSet<>(allowlist);
            if (checked) updated.add(pkg); else updated.remove(pkg);
            savePolicy(updated, autoFcm);
        });
        adapter.setChangesEnabled(false);
        ListView list = findViewById(R.id.app_list);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) {
                    ((AnimatedAppList) list).stopItemAnimations();
                    hideKeyboard();
                }
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                findViewById(R.id.root).invalidate();
            }
        });
        setupInsets();
        searchView.setOnQueryTextListener(this);
        searchView.setTitleView(findViewById(R.id.title));
        searchView.setOnExpansionChangedListener(this::setSearchExpanded);
        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean("search_expanded")) {
                searchView.setIconified(false);
                setSearchExpanded(true);
            }
            searchView.setQuery(savedInstanceState.getString("query", ""), false);
            searchView.clearFocus();
        }
        findViewById(R.id.more).setOnClickListener(this::showMenu);
        filterApps();
        initXposedService();
        loadApps();
    }

    @SuppressWarnings("deprecation")
    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            getWindow().getDecorView().setSystemUiVisibility(flags | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @SuppressWarnings("deprecation")
    private void setupInsets() {
        View root = findViewById(R.id.root);
        View header = findViewById(R.id.header);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, right, top;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                left = bars.left;
                right = bars.right;
                top = bars.top;
                bottomInset = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                top = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, 0, right, 0);
            header.setPadding(0, top, 0, 0);
            updateListInsets();
            return insets;
        });
        header.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> updateListInsets());
        root.requestApplyInsets();
    }

    private void updateListInsets() {
        ListView list = findViewById(R.id.app_list);
        list.setPadding(dp(16), findViewById(R.id.header).getHeight() + dp(8), dp(16), bottomInset + dp(20));
    }

    private void setSearchExpanded(boolean expanded) {
        if (!expanded) hideKeyboard();
        if (Build.VERSION.SDK_INT >= 33) {
            if (expanded && searchBackCallback == null) {
                searchBackCallback = this::closeSearch;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, searchBackCallback);
            } else if (!expanded && searchBackCallback != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(searchBackCallback);
                searchBackCallback = null;
            }
        }
    }

    private void closeSearch() {
        searchView.setQuery("", false);
        searchView.setIconified(true);
        setSearchExpanded(false);
    }

    @Override
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        // API 27–32 fallback. API 33+ uses the callback in setSearchExpanded().
        if (!searchView.isIconified()) closeSearch(); else super.onBackPressed();
    }

    private void hideKeyboard() {
        searchView.clearFocus();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchView.getWindowToken(), 0);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("query", searchView.getQuery().toString());
        state.putBoolean("search_expanded", !searchView.isIconified());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        boolean reloadApps = !loading;
        if (reloadApps) {
            loading = true;
            loadFailed = false;
        }
        filterApps(false);
        readPolicy();
        if (reloadApps) loadApps();
    }

    @Override
    protected void onDestroy() {
        if (menu != null) menu.dismiss();
        if (sheet != null) sheet.dismissImmediately();
        if (Build.VERSION.SDK_INT >= 33 && searchBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(searchBackCallback);
        }
        appLoader.shutdownNow();
        adapter.close();
        super.onDestroy();
    }

    @Override public boolean onQueryTextSubmit(String query) { hideKeyboard(); return true; }
    @Override public boolean onQueryTextChange(String query) { filterApps(); return true; }

    private void showMenu(View anchor) {
        hideKeyboard();
        if (menu != null) menu.dismiss();
        menu = new ManagementMenu(this);
        menu.toggle(R.id.menu_fcm_only, R.string.show_fcm_only, onlyFcm, () -> {
            onlyFcm = !onlyFcm;
            uiSettings.edit().putBoolean(ONLY_FCM, onlyFcm).apply();
            filterApps();
        });
        menu.toggle(R.id.menu_system_apps, R.string.show_system_apps, showSystemApps, () -> {
            showSystemApps = !showSystemApps;
            uiSettings.edit().putBoolean(SHOW_SYSTEM_APPS, showSystemApps).apply();
            filterApps();
        });
        menu.action(R.id.menu_clear_all, R.string.clear_all, policyReady, this::confirmClearAll);
        menu.action(R.id.menu_select_fcm, R.string.select_all, policyReady && !loading && !loadFailed, this::confirmSelectFcm);
        menu.toggle(R.id.menu_auto_fcm, R.string.auto_fcm, autoFcm, () -> savePolicy(allowlist, !autoFcm));
        menu.action(R.id.menu_diagnostics, R.string.open_diagnostics, true, this::openDiagnostics);
        menu.action(R.id.menu_about, R.string.about, true, this::showAbout);
        menu.show(anchor);
    }

    private void confirmClearAll() {
        showSheet(R.string.clear_all, getString(R.string.confirm_clear), R.string.clear_all,
                () -> savePolicy(new HashSet<>(), false), true);
    }

    private void confirmSelectFcm() {
        if (loading || loadFailed) return;
        Set<String> detected = new HashSet<>();
        for (AppListAdapter.AppEntry app : allApps) if (app.containsFcm) detected.add(app.packageName);
        if (detected.isEmpty()) {
            Toast.makeText(this, R.string.no_fcm_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        showSheet(R.string.select_all, getResources().getQuantityString(R.plurals.confirm_select_fcm,
                        detected.size(), detected.size()), R.string.select_all, () -> {
                    Set<String> updated = new HashSet<>(allowlist);
                    updated.addAll(detected);
                    savePolicy(updated, autoFcm);
                }, true);
    }

    private void showSheet(int title, CharSequence message, int positive, Runnable action, boolean confirm) {
        if (sheet != null) sheet.dismissImmediately();
        ManagementSheet dialog = new ManagementSheet(this);
        sheet = dialog;
        dialog.setTitle(title);
        dialog.setMessage(message);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(positive), (ignored, which) -> {
            if (action != null) action.run();
        });
        if (confirm) dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(android.R.string.cancel),
                (ignored, which) -> { });
        dialog.setOnDismissListener(ignored -> { if (sheet == dialog) sheet = null; });
        dialog.show();
    }

    private void openDiagnostics() {
        try {
            startActivity(new Intent().setClassName("com.google.android.gms",
                    "com.google.android.gms.gcm.GcmDiagnostics"));
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, R.string.diagnostics_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            version = getString(R.string.version_unknown);
        }
        SpannableString message = new SpannableString(getString(R.string.about_message, version));
        int github = message.toString().indexOf("GitHub");
        if (github >= 0) message.setSpan(new URLSpan("https://github.com/miaonai/HyperOS_FCM_Live"),
                github, github + "GitHub".length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        showSheet(R.string.app_name, message, android.R.string.ok, null, false);
    }

    private void filterApps() {
        filterApps(true);
    }

    private void filterApps(boolean animate) {
        AnimatedAppList list = findViewById(R.id.app_list);
        boolean ready = !loading && !loadFailed && policyReady;
        if (ready) {
            String query = searchView.getQuery().toString().trim().toLowerCase(Locale.ROOT);
            List<AppListAdapter.AppEntry> next = new ArrayList<>();
            for (AppListAdapter.AppEntry app : allApps) {
                if (!showSystemApps && app.systemApp) continue;
                if (onlyFcm && !app.containsFcm) continue;
                if (app.label.toLowerCase(Locale.ROOT).contains(query)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(query)) next.add(app);
            }
            // Stable sort preserves FCM/name order within each enabled/disabled group.
            next.sort((a, b) -> Boolean.compare(b.checked, a.checked));
            list.changeItems(animate && list.getVisibility() == View.VISIBLE, () -> {
                filteredApps.clear();
                filteredApps.addAll(next);
                adapter.notifyDataSetChanged();
            });
        } else {
            // Keep the last verified rows only as a hidden viewport anchor. Never publish a
            // package snapshot until its checked states are known, regardless of completion order.
            list.stopItemAnimations();
        }
        list.setVisibility(ready ? View.VISIBLE : View.INVISIBLE);
        TextView empty = findViewById(R.id.empty);
        empty.setText(loadFailed ? R.string.load_failed : loading ? R.string.loading_apps
                : policyReadFailed ? R.string.policy_read_failed
                : !policyReady ? (policyLoading ? R.string.loading_policy : R.string.service_unavailable)
                : R.string.no_apps);
        findViewById(R.id.empty_state).setVisibility(!ready || filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.loading).setVisibility(!loadFailed && (loading || policyLoading) ? View.VISIBLE : View.GONE);
        findViewById(R.id.root).invalidate();
    }

    private void refreshSelection() {
        refreshSelection(true);
    }

    private void refreshSelection(boolean animate) {
        Set<String> detected = new HashSet<>();
        for (AppListAdapter.AppEntry app : allApps) {
            if (app.containsFcm) detected.add(app.packageName);
        }
        Set<String> effective = FcmPolicy.effectiveAllowlist(allowlist, autoFcm, detected);
        for (AppListAdapter.AppEntry app : allApps) {
            app.automaticallyAllowed = autoFcm && app.containsFcm;
            app.checked = effective.contains(app.packageName);
        }
        filterApps(animate);
    }

    private void readPolicy() {
        if (pendingWrites != 0) return;
        policyReady = false;
        adapter.setChangesEnabled(false);
        XposedService service = xposedService;
        policyLoading = service != null;
        policyReadFailed = false;
        filterApps(false);
        if (service == null) return;
        long revision = ++policyReadRevision;
        policyWriter.execute(() -> {
            try {
                SharedPreferences prefs = service.getRemotePreferences(Prefs.GROUP_CONFIG);
                Set<String> selected = Prefs.readAllowlist(prefs);
                boolean automatic = Prefs.readAutoFcm(prefs);
                runOnUiThread(() -> {
                    if (isDestroyed() || service != xposedService || revision != policyReadRevision) return;
                    allowlist = selected;
                    autoFcm = automatic;
                    confirmedAllowlist = new HashSet<>(selected);
                    confirmedAutoFcm = automatic;
                    policyLoading = false;
                    policyReady = true;
                    adapter.setChangesEnabled(true);
                    // Reading saved policy is initialization, not a user toggling a switch.
                    refreshSelection(false);
                });
            } catch (RuntimeException ignored) {
                runOnUiThread(() -> {
                    if (isDestroyed() || service != xposedService || revision != policyReadRevision) return;
                    policyLoading = false;
                    policyReadFailed = true;
                    filterApps(false);
                });
            }
        });
    }

    private void initXposedService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(@NonNull XposedService service) {
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        xposedService = service;
                        readPolicy();
                    });
                }
                @Override public void onServiceDied(@NonNull XposedService service) {
                    runOnUiThread(() -> {
                        if (!isDestroyed() && xposedService == service) {
                            xposedService = null;
                            policyReady = false;
                            policyLoading = false;
                            policyReadFailed = false;
                            adapter.setChangesEnabled(false);
                            filterApps(false);
                        }
                    });
                }
            });
        } catch (RuntimeException ignored) {
            // Report service availability when the user attempts to save.
        }
    }

    private void savePolicy(Set<String> updated, boolean automatic) {
        XposedService service = xposedService;
        if (service == null || !policyReady) {
            Toast.makeText(this, R.string.service_unavailable, Toast.LENGTH_LONG).show();
            refreshSelection();
            return;
        }
        Set<String> snapshot = new HashSet<>(updated);
        allowlist = snapshot;
        autoFcm = automatic;
        long revision = ++policyRevision;
        pendingWrites++;
        refreshSelection();
        android.content.Context appContext = getApplicationContext();
        policyWriter.execute(() -> {
            boolean saved;
            try {
                saved = Prefs.writePolicy(appContext, service.getRemotePreferences(Prefs.GROUP_CONFIG),
                        snapshot, automatic);
            } catch (RuntimeException exception) {
                saved = false;
            }
            boolean success = saved;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                pendingWrites--;
                if (success) {
                    confirmedAllowlist = snapshot;
                    confirmedAutoFcm = automatic;
                } else if (revision == policyRevision) {
                    // An old failed write must never undo a newer selection.
                    allowlist = new HashSet<>(confirmedAllowlist);
                    autoFcm = confirmedAutoFcm;
                    refreshSelection();
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                }
                // A reconnected service may have deferred its read while this write was pending.
                if (pendingWrites == 0 && !policyReady && xposedService != null) readPolicy();
            });
        });
    }

    private void loadApps() {
        appLoader.execute(() -> {
            List<AppListAdapter.AppEntry> result = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                FcmDetector detector = new FcmDetector(pm);
                for (PackageInfo info : pm.getInstalledPackages(FcmDetector.PACKAGE_FLAGS)) {
                    if (Thread.currentThread().isInterrupted()) return;
                    ApplicationInfo ai = info.applicationInfo;
                    if (ai == null || info.packageName.equals(getPackageName())) continue;
                    AppListAdapter.AppEntry app = new AppListAdapter.AppEntry(info.packageName,
                            ai.loadLabel(pm).toString(), detector.containsFcm(info));
                    app.systemApp = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    result.add(app);
                }
                result.sort((a, b) -> {
                    if (a.containsFcm != b.containsFcm) return a.containsFcm ? -1 : 1;
                    int order = a.label.compareToIgnoreCase(b.label);
                    return order != 0 ? order : a.packageName.compareTo(b.packageName);
                });
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loading = false;
                    loadFailed = false;
                    allApps.clear();
                    allApps.addAll(result);
                    refreshSelection(false);
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loading = false;
                    loadFailed = true;
                    filterApps(false);
                });
            }
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
