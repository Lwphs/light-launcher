package com.lwphs.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final long SEARCH_DELAY_MS = 400;
    private static final int MAX_RESULTS = 28; // 4 rows of 7 posters
    private static final String FILMIN = "com.filmin.androidtv";
    private static final String FILMIN_TV_LINK = "https://androidtv.filmin.com/es";

    private GridLayout grid;
    private View watchingSection;
    private LinearLayout watching;
    private GridLayout results;
    private TextView status;
    private View info;
    private TextView infoTitle, infoWhere;
    private EditText search;
    // packageName -> time it was last opened from this launcher
    private SharedPreferences lastUsed;
    // which streaming services the search covers (stores the ones turned off)
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Runnable runSearch = this::runSearch;
    // Bumped on every new search so late answers to old ones are ignored.
    private int searchSeq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.background).setBackground(new RgbBackground());
        // The shadow under the top bar fades in as soon as something has scrolled under it, and
        // the frosted bar redraws its blurred copy whenever what's under it may have changed.
        View topShadow = findViewById(R.id.top_shadow);
        View scroll = findViewById(R.id.scroll);
        FrostedBar frosted = findViewById(R.id.frosted);
        frosted.follow(scroll, dp(12));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            // At the top of the list the bar stays clear; panel and shadow fade in as it scrolls.
            float shown = Math.min(1f, y / dp(16));
            topShadow.setAlpha(shown);
            frosted.setAlpha(shown);
            frosted.invalidate();
        });
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(frosted::invalidate);
        scroll.getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> frosted.postInvalidateDelayed(200)); // after the focus animation
        grid = findViewById(R.id.grid);
        watchingSection = findViewById(R.id.watching_section);
        watching = findViewById(R.id.watching);
        results = findViewById(R.id.results);
        status = findViewById(R.id.status);
        info = findViewById(R.id.info);
        infoTitle = findViewById(R.id.info_title);
        infoWhere = findViewById(R.id.info_where);
        search = findViewById(R.id.search);
        lastUsed = getSharedPreferences("last_used", MODE_PRIVATE);
        prefs = getSharedPreferences("search", MODE_PRIVATE);

        View settings = findViewById(R.id.settings);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_SETTINGS)));
        settings.setOnFocusChangeListener(this::onTileFocus);

        View clear = findViewById(R.id.clear);
        clear.setOnClickListener(v -> {
            search.setText("");
            search.requestFocus();
        });
        clear.setOnFocusChangeListener(this::onTileFocus);

        View filter = findViewById(R.id.filter);
        filter.setOnClickListener(v -> showFilter());
        filter.setOnFocusChangeListener(this::onTileFocus);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(runSearch);
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (s.toString().trim().isEmpty()) {
                    showApps();
                } else {
                    handler.postDelayed(runSearch, SEARCH_DELAY_MS);
                }
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            handler.removeCallbacks(runSearch);
            runSearch();
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
            return true;
        });

        // Back clears the search; with no search there is nowhere to go, so it does nothing.
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (search.length() > 0) {
                        search.setText("");
                        search.requestFocus();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming home always shows the apps, rebuilt to pick up installs, the new order and
        // what was just watched.
        search.setText("");
        loadWatching();
        loadApps();
        linkHome();
        View first = watching.getChildCount() > 0 ? watching.getChildAt(0) : grid.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        searchExecutor.shutdownNow();
        imageExecutor.shutdownNow();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
        apps.removeIf(r -> r.activityInfo.packageName.equals(getPackageName()));

        Map<ResolveInfo, String> labels = new HashMap<>();
        for (ResolveInfo r : apps) labels.put(r, r.loadLabel(pm).toString());

        // Most recently opened first; never-opened apps (time 0) follow alphabetically.
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> {
            long ta = lastUsed.getLong(a.activityInfo.packageName, 0);
            long tb = lastUsed.getLong(b.activityInfo.packageName, 0);
            if (ta != tb) return Long.compare(tb, ta);
            return collator.compare(labels.get(a), labels.get(b));
        });

        grid.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (ResolveInfo r : apps) {
            ActivityInfo info = r.activityInfo;
            View tile = inflater.inflate(R.layout.tile, grid, false);
            ImageView image = tile.findViewById(R.id.image);
            TextView label = tile.findViewById(R.id.label);

            Drawable banner = info.loadBanner(pm);
            if (banner != null) {
                image.setImageDrawable(banner);
            } else {
                // No TV banner: show the icon with the app name under it.
                int pad = getResources().getDimensionPixelSize(R.dimen.icon_padding);
                image.setPadding(pad, pad / 2, pad, pad + pad / 2);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageDrawable(info.loadIcon(pm));
                label.setText(labels.get(r));
                label.setVisibility(View.VISIBLE);
            }

            tile.setContentDescription(labels.get(r));
            tile.setOnClickListener(v -> launch(info));
            tile.setOnFocusChangeListener(this::onTileFocus);
            grid.addView(tile);
        }
    }

    /** On the home screen the top row is "Continue watching" if there is any, else the apps. */
    private void linkHome() {
        if (watching.getChildCount() > 0) linkTopRow(watching, watching.getChildCount());
        else if (grid.getChildCount() > 0) linkTopRow(grid, grid.getColumnCount());
    }

    /**
     * Down from the search bar goes to the first view of {@code row}, and up from its first
     * {@code topCount} views goes to the search bar (not to whichever button happens to be above).
     */
    private void linkTopRow(ViewGroup row, int topCount) {
        View first = row.getChildAt(0);
        first.setId(View.generateViewId());
        search.setNextFocusDownId(first.getId());
        for (int i = 0; i < Math.min(topCount, row.getChildCount()); i++) {
            row.getChildAt(i).setNextFocusUpId(R.id.search);
        }
    }

    // ---- Continue watching ----

    private void loadWatching() {
        watching.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (WatchHistory.Entry e : WatchHistory.load(this)) {
            if (e.hasLength() && e.left() < WatchHistory.FINISHED_MS) continue; // already watched to the end
            View card = inflater.inflate(R.layout.watch_card, watching, false);
            ImageView image = card.findViewById(R.id.watch_image);
            TextView name = card.findViewById(R.id.watch_title);
            name.setText(cardTitle(e));
            ProgressBar progress = card.findViewById(R.id.watch_progress);
            if (e.hasLength()) {
                // Time left and how far along it is.
                ((TextView) card.findViewById(R.id.watch_left)).setText(timeLeft(e.left()));
                progress.setProgress((int) (1000 * e.position / e.duration));
            } else {
                // The app doesn't say how long it is (Prime Video): only how much was watched.
                ((TextView) card.findViewById(R.id.watch_left))
                        .setText(getString(R.string.min_watched, Math.max(1, e.position / 60_000)));
                progress.setVisibility(View.GONE);
            }

            if (e.image != null) loadPoster(image, e.image);
            else if (e.art != null) image.setImageBitmap(BitmapFactory.decodeFile(e.art));
            if (!e.lookedUp) lookUp(e, image, name);

            card.setContentDescription(e.title);
            card.setOnClickListener(v -> openWatched(e));
            card.setOnFocusChangeListener(this::onTileFocus);
            watching.addView(card);
        }
        watchingSection.setVisibility(watching.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private String timeLeft(long ms) {
        long minutes = (ms + 59_999) / 60_000;
        return minutes >= 60
                ? getString(R.string.h_min_left, minutes / 60, minutes % 60)
                : getString(R.string.min_left, minutes);
    }

    /** "Episode · Show"; for apps that sent no name yet (Prime Video before its lookup), the app's name. */
    private String cardTitle(WatchHistory.Entry e) {
        if (e.title.isEmpty()) {
            try {
                return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(e.pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException ex) {
                return e.pkg;
            }
        }
        return e.subtitle.isEmpty() ? e.title : e.title + " · " + e.subtitle;
    }

    /**
     * Looks the title up once: on Prime Video's page for Prime (which sends only an ID), else on
     * JustWatch. Gives a better picture, the name if it was missing, and a link straight to it.
     */
    private void lookUp(WatchHistory.Entry e, ImageView image, TextView name) {
        if (e.pkg.equals(PrimeVideo.PACKAGE) && PrimeVideo.isContentId(e.mediaId)) {
            searchExecutor.execute(() -> {
                PrimeVideo.Page page;
                try {
                    page = PrimeVideo.page(e.mediaId);
                } catch (Exception ex) {
                    return; // offline: try again next time
                }
                if (page != null) {
                    e.title = page.name;
                    e.image = page.image;
                }
                e.link = PrimeVideo.link(e.mediaId);
                e.lookedUp = true;
                WatchHistory.update(this, e);
                runOnUiThread(() -> {
                    name.setText(cardTitle(e));
                    if (e.image != null) loadPoster(image, e.image);
                });
            });
            return;
        }

        JustWatch.Service service = JustWatch.serviceForPackage(e.pkg);
        if (service == null) return; // not a streaming service (e.g. YouTube): keep the app's own artwork
        searchExecutor.execute(() -> {
            JustWatch.Title found = null;
            try {
                // Apps differ in which field holds the show's name, so try both.
                for (String candidate : new String[]{e.title, e.subtitle}) {
                    if (found != null || candidate.isEmpty()) continue;
                    JustWatch.Title t = JustWatch.find(candidate, service);
                    if (t != null && sameName(t.name, candidate)) found = t;
                }
            } catch (Exception ex) {
                return; // offline: try again next time
            }
            if (found != null) {
                e.image = found.backdropUrl;
                if (!found.offers.isEmpty()) e.link = found.offers.get(0).url;
            }
            e.lookedUp = true;
            WatchHistory.update(this, e);
            String url = e.image;
            if (url != null) runOnUiThread(() -> loadPoster(image, url));
        });
    }

    /** Loose name match, so a lookup never attaches some unrelated title. */
    private static boolean sameName(String a, String b) {
        String x = a.toLowerCase(), y = b.toLowerCase();
        return x.contains(y) || y.contains(x);
    }

    private void openWatched(WatchHistory.Entry e) {
        if (e.link != null) {
            openLink(e.pkg, e.link);
            return;
        }
        Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
        if (app == null) app = getPackageManager().getLaunchIntentForPackage(e.pkg);
        if (app == null) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        lastUsed.edit().putLong(e.pkg, System.currentTimeMillis()).apply();
    }

    private void launch(ActivityInfo info) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(info.packageName, info.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            lastUsed.edit().putLong(info.packageName, System.currentTimeMillis()).apply();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    /** Focused tiles grow just a little and get a soft white glow (a white elevation shadow). */
    private void onTileFocus(View v, boolean focused) {
        v.setOutlineSpotShadowColor(Color.WHITE);
        v.setOutlineAmbientShadowColor(Color.WHITE);
        float scale = focused ? 1.02f : 1f;
        v.animate().scaleX(scale).scaleY(scale).translationZ(focused ? dp(5) : 0f).setDuration(120).start();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ---- Search ----

    /** Services the filter has not turned off. The filter always lists every service (installed or not). */
    private List<JustWatch.Service> selectedServices() {
        Set<String> off = prefs.getStringSet("off", new HashSet<>());
        List<JustWatch.Service> selected = new ArrayList<>(Arrays.asList(JustWatch.SERVICES));
        selected.removeIf(s -> off.contains(s.shortName));
        return selected;
    }

    private void showFilter() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_filter);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        LinearLayout list = dialog.findViewById(R.id.filter_list);
        View done = dialog.findViewById(R.id.filter_done);
        done.setOnClickListener(v -> dialog.dismiss());

        // Selected rows show inverted (white, black text) with a check; each tap is saved right away,
        // so there is no Cancel to hit by accident with the remote.
        Set<String> off = new HashSet<>(prefs.getStringSet("off", new HashSet<>()));
        for (JustWatch.Service s : JustWatch.SERVICES) {
            View row = getLayoutInflater().inflate(R.layout.filter_row, list, false);
            ((TextView) row.findViewById(R.id.filter_name)).setText(s.name);
            if (!isInstalled(s.pkg)) row.findViewById(R.id.filter_missing).setVisibility(View.VISIBLE);
            row.setSelected(!off.contains(s.shortName));
            row.setOnClickListener(v -> {
                boolean nowOn = !v.isSelected();
                v.setSelected(nowOn);
                if (nowOn) off.remove(s.shortName);
                else off.add(s.shortName);
                prefs.edit().putStringSet("off", new HashSet<>(off)).apply();
                // Move on to the next service, or to Done after the last one.
                int next = list.indexOfChild(v) + 1;
                (next < list.getChildCount() ? list.getChildAt(next) : done).requestFocus();
            });
            // No zoom or border here: focus just shows as an in-between shade (filter_row_bg).
            list.addView(row);
        }

        dialog.setOnDismissListener(d -> {
            if (search.length() > 0) runSearch();
        });
        dialog.show();
        // 420dp wide; as tall as its content, but at most 80% of the screen (like CSS max-height).
        int width = (int) dp(420);
        View root = dialog.findViewById(R.id.filter_root);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
        dialog.getWindow().setLayout(width, Math.min(root.getMeasuredHeight(), maxHeight));
        list.getChildAt(0).requestFocus();
    }

    private void showApps() {
        searchSeq++; // drop any search still in flight
        results.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        info.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        watchingSection.setVisibility(watching.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        linkHome();
    }

    private void showStatus(int message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void runSearch() {
        String query = search.getText().toString().trim();
        if (query.isEmpty()) return;
        grid.setVisibility(View.GONE);
        watchingSection.setVisibility(View.GONE);
        results.removeAllViews();
        results.setVisibility(View.VISIBLE);
        info.setVisibility(View.GONE);

        List<JustWatch.Service> services = selectedServices();
        if (services.isEmpty()) {
            showStatus(R.string.no_services_selected);
            return;
        }
        showStatus(R.string.searching);

        int seq = ++searchSeq;
        searchExecutor.execute(() -> {
            List<JustWatch.Title> titles;
            try {
                titles = JustWatch.search(query, services, MAX_RESULTS);
            } catch (Exception e) {
                titles = null;
            }
            List<JustWatch.Title> found = titles;
            runOnUiThread(() -> {
                if (seq != searchSeq) return;
                if (found == null) showStatus(R.string.search_failed);
                else if (found.isEmpty()) showStatus(R.string.no_results);
                else showResults(found);
            });
        });
    }

    private void showResults(List<JustWatch.Title> titles) {
        status.setVisibility(View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        for (JustWatch.Title title : titles) {
            View tile = inflater.inflate(R.layout.result, results, false);
            ImageView poster = tile.findViewById(R.id.poster);
            TextView name = tile.findViewById(R.id.name);
            TextView where = tile.findViewById(R.id.where);

            where.setText(services(title));

            if (title.posterUrl == null) {
                name.setText(title.name);
                name.setVisibility(View.VISIBLE);
            } else {
                loadPoster(poster, title.posterUrl);
            }

            tile.setContentDescription(nameWithYear(title));
            tile.setOnClickListener(v -> openTitle(title));
            tile.setOnFocusChangeListener((v, focused) -> {
                onTileFocus(v, focused);
                if (focused) showInfo(title);
            });
            results.addView(tile);
        }
        linkTopRow(results, results.getColumnCount());
        showInfo(titles.get(0));
        info.setVisibility(View.VISIBLE);
    }

    /** The info bar above the results shows the focused title. */
    private void showInfo(JustWatch.Title title) {
        infoTitle.setText(nameWithYear(title));
        infoWhere.setText(services(title));
    }

    private static String nameWithYear(JustWatch.Title title) {
        return title.year > 0 ? title.name + " (" + title.year + ")" : title.name;
    }

    /** "Netflix · Filmin"; services whose app isn't installed are shown in grey. */
    private CharSequence services(JustWatch.Title title) {
        SpannableStringBuilder services = new SpannableStringBuilder();
        for (JustWatch.Offer o : title.offers) {
            if (services.length() > 0) services.append(" · ");
            services.append(isInstalled(o.service.pkg) ? o.service.name : grey(o.service.name));
        }
        return services;
    }

    private void loadPoster(ImageView view, String url) {
        view.setTag(url);
        Bitmap cached = posters.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        imageExecutor.execute(() -> {
            Bitmap bitmap;
            try {
                bitmap = Net.image(url); // size-limited, allowed image servers only
            } catch (Exception e) {
                return;
            }
            if (bitmap == null) return;
            posters.put(url, bitmap);
            runOnUiThread(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private void openTitle(JustWatch.Title title) {
        if (title.offers.size() == 1) {
            openOffer(title.offers.get(0));
            return;
        }
        // Services whose app isn't installed are greyed out with "not installed".
        CharSequence[] names = new CharSequence[title.offers.size()];
        for (int i = 0; i < names.length; i++) {
            JustWatch.Service s = title.offers.get(i).service;
            names[i] = isInstalled(s.pkg) ? s.name
                    : grey(s.name + " · " + getString(R.string.not_installed));
        }
        new AlertDialog.Builder(this)
                .setTitle(title.name)
                .setItems(names, (d, which) -> openOffer(title.offers.get(which)))
                .show();
    }

    /** Opens the title in its app, or that app's Play Store page if it isn't installed. */
    private void openOffer(JustWatch.Offer offer) {
        if (!isInstalled(offer.service.pkg)) {
            Toast.makeText(this, getString(R.string.app_not_installed, offer.service.name), Toast.LENGTH_SHORT).show();
            openPlayStore(offer.service.pkg);
            return;
        }
        openLink(offer.service.pkg, offer.url);
    }

    /** Whether {@code pkg} is installed as a TV app (debloated apps count as missing). */
    private boolean isInstalled(String pkg) {
        return getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
    }

    private void openPlayStore(String pkg) {
        Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(store);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    private CharSequence grey(String text) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new ForegroundColorSpan(getColor(R.color.hint)), 0, text.length(), 0);
        return s;
    }

    /** Opens {@code url} in app {@code pkg}; if the app doesn't take the link, opens the app itself. */
    private void openLink(String pkg, String url) {
        if (pkg.equals(FILMIN) && !url.startsWith(FILMIN_TV_LINK)) {
            // Filmin only takes its own TV link, worked out from the title's web page.
            searchExecutor.execute(() -> {
                String tvLink;
                try {
                    tvLink = Filmin.tvLink(url);
                } catch (Exception e) {
                    tvLink = null;
                }
                String link = tvLink != null ? tvLink : FILMIN_TV_LINK; // no ID: just open the app
                runOnUiThread(() -> openLink(pkg, link));
            });
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // A running Filmin ignores new links, so it starts over with this one.
        if (pkg.equals(FILMIN)) view.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (app == null) {
                Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        lastUsed.edit().putLong(pkg, System.currentTimeMillis()).apply();
    }
}
