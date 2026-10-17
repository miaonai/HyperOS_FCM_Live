package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppListAdapter extends BaseAdapter {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(String packageName, boolean checked);
    }

    public static class AppEntry {
        public final String packageName;
        public final String label;
        public Drawable icon;   // loaded lazily, null until resolved
        public volatile boolean iconLoading;
        public boolean iconLoaded;
        public boolean checked;
        public boolean automaticallyAllowed;
        public boolean systemApp;
        public final boolean containsFcm;

        public AppEntry(String packageName, String label, boolean containsFcm) {
            this.packageName = packageName;
            this.label = label;
            this.containsFcm = containsFcm;
        }
    }

    private final LayoutInflater inflater;
    private final PackageManager pm;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService iconLoader = Executors.newFixedThreadPool(4);
    private final List<AppEntry> apps;
    private final OnCheckedChangeListener listener;
    private final Map<String, Long> identities = new HashMap<>();
    private final Map<ViewHolder, Boolean> holders = new WeakHashMap<>();

    public AppListAdapter(Context context, List<AppEntry> apps, OnCheckedChangeListener listener) {
        this.inflater = LayoutInflater.from(context);
        this.pm = context.getPackageManager();
        this.apps = apps;
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return apps.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return apps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return identities.computeIfAbsent(getItem(position).packageName, ignored -> (long) identities.size());
    }

    @Override public boolean hasStableIds() { return true; }

    static String boundPackage(View row) {
        return row.getTag() instanceof ViewHolder holder ? holder.boundPackage : null;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_app, parent, false);
            holder = new ViewHolder();
            holder.icon = convertView.findViewById(R.id.app_icon);
            holder.label = convertView.findViewById(R.id.app_label);
            holder.pkg = convertView.findViewById(R.id.app_pkg);
            holder.check = convertView.findViewById(R.id.app_check);
            convertView.setTag(holder);
            holders.put(holder, Boolean.TRUE);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        AppEntry app = getItem(position);
        holder.label.setAppName(app.label, app.containsFcm);
        holder.pkg.setText(app.packageName);
        if (app.icon != null) {
            holder.icon.setImageDrawable(app.icon);
        } else {
            // Placeholder while the icon loads off the main thread.
            holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            loadIcon(app);
        }
        holder.check.setOnCheckedChangeListener(null);
        holder.check.setChecked(app.checked);
        if (!app.packageName.equals(holder.boundPackage)) {
            convertView.animate().cancel();
            convertView.setTranslationY(0);
            holder.check.jumpDrawablesToCurrentState();
        }
        holder.boundPackage = app.packageName;
        holder.check.setEnabled(!app.automaticallyAllowed);
        holder.check.setAlpha(app.automaticallyAllowed ? 0.55f : 1f);
        holder.check.setContentDescription(inflater.getContext().getString(
                app.automaticallyAllowed ? R.string.auto_allowed_app : R.string.allow_wake_app, app.label));
        holder.check.setOnCheckedChangeListener((buttonView, isChecked) -> {
            app.checked = isChecked;
            if (listener != null) {
                listener.onCheckedChanged(app.packageName, isChecked);
            }
        });
        convertView.setOnClickListener(v -> {
            if (app.automaticallyAllowed) {
                android.widget.Toast.makeText(inflater.getContext(), R.string.auto_fcm_managed,
                        android.widget.Toast.LENGTH_SHORT).show();
            } else {
                holder.check.performClick();
            }
        });
        return convertView;
    }

    void close() {
        iconLoader.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    /** Update only holders still bound to this package; icon loads never rebuild the list. */
    private void loadIcon(final AppEntry app) {
        if (app.iconLoaded || app.iconLoading || iconLoader.isShutdown()) {
            return;
        }
        app.iconLoading = true;
        iconLoader.execute(() -> {
            Drawable d;
            try {
                d = pm.getApplicationIcon(app.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                d = null;
            }
            final Drawable loaded = d;
            mainHandler.post(() -> {
                if (iconLoader.isShutdown()) return;
                app.iconLoading = false;
                app.iconLoaded = true;
                app.icon = loaded;
                if (loaded != null) {
                    for (ViewHolder holder : holders.keySet()) {
                        if (app.packageName.equals(holder.boundPackage)) holder.icon.setImageDrawable(loaded);
                    }
                }
            });
        });
    }

    private static class ViewHolder {
        ImageView icon;
        FcmLabelView label;
        TextView pkg;
        CapsuleSwitch check;
        String boundPackage;
    }
}
