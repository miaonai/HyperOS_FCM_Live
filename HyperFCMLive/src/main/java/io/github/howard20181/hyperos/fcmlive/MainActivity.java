package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

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

/** Manual FCM wake allowlist for China HyperOS. */
public class MainActivity extends Activity implements SearchView.OnQueryTextListener {
    private final List<AppListAdapter.AppEntry> allApps = new ArrayList<>();
    private final List<AppListAdapter.AppEntry> filteredApps = new ArrayList<>();
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();
    private Set<String> allowlist = new HashSet<>();
    private AppListAdapter adapter;
    private SearchView searchView;
    private boolean showSystemApps;
    private boolean onlyFcm;
    private boolean loading = true;
    private XposedService xposedService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Respect status/navigation bars, including enforced edge-to-edge on newer Android.
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();

        if (savedInstanceState != null) {
            showSystemApps = savedInstanceState.getBoolean("system");
            onlyFcm = savedInstanceState.getBoolean("fcm");
        }
        searchView = findViewById(R.id.search_view);
        adapter = new AppListAdapter(this, filteredApps, (pkg, checked) -> {
            Set<String> updated = new HashSet<>(allowlist);
            if (checked) updated.add(pkg); else updated.remove(pkg);
            saveAllowlist(updated);
        });
        ((ListView) findViewById(R.id.app_list)).setAdapter(adapter);
        searchView.setOnQueryTextListener(this);
        if (savedInstanceState != null) {
            searchView.setQuery(savedInstanceState.getString("query", ""), false);
        }
        CheckBox system = findViewById(R.id.cb_system_apps);
        system.setChecked(showSystemApps);
        system.setOnCheckedChangeListener((button, checked) -> {
            showSystemApps = checked;
            filterApps();
        });
        CheckBox fcm = findViewById(R.id.cb_fcm_only);
        fcm.setChecked(onlyFcm);
        fcm.setOnCheckedChangeListener((button, checked) -> {
            onlyFcm = checked;
            filterApps();
        });
        findViewById(R.id.btn_select_all).setOnClickListener(v -> setVisibleChecked(true));
        findViewById(R.id.btn_clear_all).setOnClickListener(v -> setVisibleChecked(false));
        initXposedService();
        loadApps();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("system", showSystemApps);
        state.putBoolean("fcm", onlyFcm);
        state.putString("query", searchView.getQuery().toString());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        appLoader.shutdownNow();
        adapter.close();
        super.onDestroy();
    }

    @Override
    public boolean onQueryTextSubmit(String query) { return false; }

    @Override
    public boolean onQueryTextChange(String query) {
        filterApps();
        return true;
    }

    private void filterApps() {
        String query = searchView.getQuery().toString().trim().toLowerCase(Locale.ROOT);
        filteredApps.clear();
        for (AppListAdapter.AppEntry app : allApps) {
            if (!showSystemApps && app.systemApp) continue;
            if (onlyFcm && !app.containsFcm) continue;
            if (app.label.toLowerCase(Locale.ROOT).contains(query)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                filteredApps.add(app);
            }
        }
        adapter.notifyDataSetChanged();
        TextView empty = findViewById(R.id.empty);
        if (!loading) empty.setText(R.string.no_apps);
        empty.setVisibility(filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.app_count)).setText(getString(R.string.app_count,
                filteredApps.size(), allowlist.size()));
    }

    private void setVisibleChecked(boolean checked) {
        Set<String> updated = new HashSet<>(allowlist);
        for (AppListAdapter.AppEntry app : filteredApps) {
            if (checked) updated.add(app.packageName); else updated.remove(app.packageName);
        }
        saveAllowlist(updated);
    }

    private void refreshSelection() {
        for (AppListAdapter.AppEntry app : allApps) {
            app.checked = allowlist.contains(app.packageName);
        }
        allApps.sort((a, b) -> {
            if (a.checked != b.checked) return a.checked ? -1 : 1;
            if (a.containsFcm != b.containsFcm) return a.containsFcm ? -1 : 1;
            int order = a.label.compareToIgnoreCase(b.label);
            return order != 0 ? order : a.packageName.compareTo(b.packageName);
        });
        filterApps();
    }

    private void initXposedService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        xposedService = service;
                        SharedPreferences prefs = remotePrefs();
                        if (prefs != null) {
                            allowlist = Prefs.readAllowlist(prefs);
                            ((TextView) findViewById(R.id.service_status)).setText(R.string.service_connected);
                            refreshSelection();
                        }
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    runOnUiThread(() -> {
                        if (isDestroyed() || xposedService != service) return;
                        xposedService = null;
                        ((TextView) findViewById(R.id.service_status)).setText(R.string.service_unavailable);
                    });
                }
            });
        } catch (RuntimeException e) {
            // The initial status already explains that LSPosed is unavailable.
        }
    }

    private SharedPreferences remotePrefs() {
        if (xposedService == null) return null;
        try {
            return xposedService.getRemotePreferences(Prefs.GROUP_CONFIG);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void saveAllowlist(Set<String> updated) {
        SharedPreferences prefs = remotePrefs();
        if (prefs == null) {
            Toast.makeText(this, R.string.service_unavailable, Toast.LENGTH_LONG).show();
        } else {
            try {
                if (Prefs.writeAllowlist(this, prefs, updated)) {
                    allowlist = updated;
                } else {
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                }
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
            }
        }
        refreshSelection();
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
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loading = false;
                    findViewById(R.id.loading).setVisibility(View.GONE);
                    allApps.clear();
                    allApps.addAll(result);
                    // Read the latest selection only on the main thread.
                    refreshSelection();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    findViewById(R.id.loading).setVisibility(View.GONE);
                    ((TextView) findViewById(R.id.empty)).setText(R.string.load_failed);
                });
            }
        });
    }
}
