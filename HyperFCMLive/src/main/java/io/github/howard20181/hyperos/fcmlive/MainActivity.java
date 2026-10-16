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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.LinearLayout;
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
    private Set<String> allowlist = new HashSet<>();
    private AppListAdapter adapter;
    private SearchView searchView;
    private SharedPreferences uiSettings;
    private boolean showSystemApps;
    private boolean onlyFcm;
    private boolean autoFcm;
    private boolean loading = true;
    private boolean loadFailed;
    private int bottomInset;
    private XposedService xposedService;
    private ManagementMenu menu;
    private OnBackInvokedCallback searchBackCallback;
    private final Runnable reorderApps = this::filterApps;

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
            savePolicy(updated, autoFcm, true);
        });
        ListView list = findViewById(R.id.app_list);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                findViewById(R.id.root).invalidate();
            }
        });
        setupInsets();
        searchView.setOnQueryTextListener(this);
        searchView.setOnSearchClickListener(view -> setSearchExpanded(true));
        searchView.setOnCloseListener(() -> { setSearchExpanded(false); return false; });
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
        findViewById(R.id.title).setVisibility(expanded ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) searchView.getLayoutParams();
        params.width = expanded ? 0 : dp(48);
        params.weight = expanded ? 1 : 0;
        searchView.setLayoutParams(params);
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
        readPolicy();
        if (!loading) {
            loading = true;
            loadFailed = false;
            loadApps();
        }
    }

    @Override
    protected void onDestroy() {
        findViewById(R.id.root).removeCallbacks(reorderApps);
        if (menu != null) menu.dismiss();
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
        menu.action(R.id.menu_clear_all, R.string.clear_all, true, this::confirmClearAll);
        menu.action(R.id.menu_select_fcm, R.string.select_all, !loading && !loadFailed, this::confirmSelectFcm);
        menu.toggle(R.id.menu_auto_fcm, R.string.auto_fcm, autoFcm, () -> savePolicy(allowlist, !autoFcm));
        menu.action(R.id.menu_diagnostics, R.string.open_diagnostics, true, this::openDiagnostics);
        menu.action(R.id.menu_about, R.string.about, true, this::showAbout);
        menu.show(anchor);
    }

    private void confirmClearAll() {
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.clear_all)
                .setMessage(R.string.confirm_clear)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_all, (dialog, which) -> savePolicy(new HashSet<>(), false)));
    }

    private void confirmSelectFcm() {
        if (loading || loadFailed) return;
        Set<String> detected = new HashSet<>();
        for (AppListAdapter.AppEntry app : allApps) if (app.containsFcm) detected.add(app.packageName);
        if (detected.isEmpty()) {
            Toast.makeText(this, R.string.no_fcm_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.select_all)
                .setMessage(getResources().getQuantityString(R.plurals.confirm_select_fcm,
                        detected.size(), detected.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.select_all, (dialog, which) -> {
                    Set<String> updated = new HashSet<>(allowlist);
                    updated.addAll(detected);
                    savePolicy(updated, autoFcm);
                }));
    }

    private void showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            GradientDrawable background = new GradientDrawable();
            background.setColor(getColor(R.color.surface));
            background.setCornerRadius(dp(28));
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(background);
                dialog.getWindow().setDimAmount(0.25f);
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false);
        });
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
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.about)
                .setMessage(getString(R.string.about_message, version))
                .setPositiveButton(android.R.string.ok, null));
    }

    private void filterApps() {
        findViewById(R.id.root).removeCallbacks(reorderApps);
        String query = searchView.getQuery().toString().trim().toLowerCase(Locale.ROOT);
        filteredApps.clear();
        for (AppListAdapter.AppEntry app : allApps) {
            if (!showSystemApps && app.systemApp) continue;
            if (onlyFcm && !app.containsFcm) continue;
            if (app.label.toLowerCase(Locale.ROOT).contains(query)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(query)) filteredApps.add(app);
        }
        // Stable sort preserves FCM/name order within each enabled/disabled group.
        filteredApps.sort((a, b) -> Boolean.compare(b.checked, a.checked));
        adapter.notifyDataSetChanged();
        TextView empty = findViewById(R.id.empty);
        empty.setText(loadFailed ? R.string.load_failed : loading ? R.string.loading_apps : R.string.no_apps);
        findViewById(R.id.empty_state).setVisibility(filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.loading).setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.root).invalidate();
    }

    private void refreshSelection() {
        refreshSelection(false);
    }

    private void refreshSelection(boolean deferReorder) {
        Set<String> detected = new HashSet<>();
        for (AppListAdapter.AppEntry app : allApps) {
            if (app.containsFcm) detected.add(app.packageName);
        }
        Set<String> effective = FcmPolicy.effectiveAllowlist(allowlist, autoFcm, detected);
        for (AppListAdapter.AppEntry app : allApps) {
            app.automaticallyAllowed = autoFcm && app.containsFcm;
            app.checked = effective.contains(app.packageName);
        }
        if (deferReorder) {
            // Let the clicked thumb finish moving before moving its row to a different group.
            adapter.notifyDataSetChanged();
            View root = findViewById(R.id.root);
            root.removeCallbacks(reorderApps);
            root.postDelayed(reorderApps, CapsuleSwitch.SETTLE_DURATION_MS);
        } else filterApps();
    }

    private void readPolicy() {
        SharedPreferences prefs = remotePrefs();
        if (prefs == null) return;
        try {
            allowlist = Prefs.readAllowlist(prefs);
            autoFcm = Prefs.readAutoFcm(prefs);
            refreshSelection();
        } catch (RuntimeException ignored) {
            // Keep the last known state if the service disappears during the read.
        }
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
                        if (!isDestroyed() && xposedService == service) xposedService = null;
                    });
                }
            });
        } catch (RuntimeException ignored) {
            // Report service availability when the user attempts to save.
        }
    }

    private SharedPreferences remotePrefs() {
        if (xposedService == null) return null;
        try { return xposedService.getRemotePreferences(Prefs.GROUP_CONFIG); }
        catch (RuntimeException exception) { return null; }
    }

    private void savePolicy(Set<String> updated, boolean automatic) {
        savePolicy(updated, automatic, false);
    }

    private void savePolicy(Set<String> updated, boolean automatic, boolean deferReorder) {
        SharedPreferences prefs = remotePrefs();
        if (prefs == null) {
            Toast.makeText(this, R.string.service_unavailable, Toast.LENGTH_LONG).show();
        } else {
            try {
                if (Prefs.writePolicy(this, prefs, updated, automatic)) {
                    allowlist = new HashSet<>(updated);
                    autoFcm = automatic;
                } else Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
            } catch (RuntimeException exception) {
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
            }
        }
        refreshSelection(deferReorder);
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
                    refreshSelection();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loading = false;
                    loadFailed = true;
                    filterApps();
                });
            }
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
