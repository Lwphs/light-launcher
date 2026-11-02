package com.lwphs.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The last titles watched in any app (read from their media sessions), newest first. Stored on the TV only. */
final class WatchHistory {

    static final int MAX = 10;
    private static final long MIN_DURATION_MS = 5 * 60_000;      // skips trailers, previews and songs
    private static final long MIN_WATCHED_MS = 30_000;           // skips things only just started (autoplay)
    private static final long MIN_WATCHED_NO_LENGTH_MS = 2 * 60_000; // same, when the app doesn't say how long it is
    static final long FINISHED_MS = 2 * 60_000;                  // less than this left = watched, hidden

    static final class Entry {
        String pkg, title, subtitle;
        String mediaId;   // the app's own ID for the title (Prime Video sends only this); may be empty
        long duration;    // 0 when the app doesn't say
        long position, updated;
        String art;       // the app's own artwork, saved as a local file; may be null
        String image;     // picture URL (JustWatch or the app's page), once looked up
        String link;      // link straight to the title in its app, once looked up
        String series = ""; // the film or show the lookup found, so episodes group; "" if none
        boolean lookedUp;

        String key() {
            return mediaId.isEmpty() ? pkg + "|" + title + "|" + subtitle : pkg + "|" + mediaId;
        }

        boolean hasLength() {
            return duration > 0;
        }

        long left() {
            return Math.max(0, duration - position);
        }

        /** Watched to the end: hidden from "Continue watching". */
        boolean finished() {
            return hasLength() && left() < FINISHED_MS;
        }

        /** Same app and same film or series as {@code other}, by their lookups: another episode counts. */
        boolean sameSeries(Entry other) {
            return !series.isEmpty() && pkg.equals(other.pkg) && series.equalsIgnoreCase(other.series);
        }

        /** Takes what a lookup found for this title: picture, link, series, and the name if the app sent none. */
        void copyLookup(Entry looked) {
            if (title.isEmpty()) title = looked.title;
            image = looked.image;
            link = looked.link;
            series = looked.series;
            lookedUp = looked.lookedUp;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("pkg", pkg).put("title", title).put("subtitle", subtitle)
                    .put("mediaId", mediaId).put("duration", duration).put("position", position)
                    .put("updated", updated).put("art", art).put("image", image).put("link", link)
                    .put("series", series).put("lookedUp", lookedUp);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.pkg = o.optString("pkg");
            e.title = o.optString("title");
            e.subtitle = o.optString("subtitle");
            e.mediaId = o.optString("mediaId");
            e.duration = o.optLong("duration");
            e.position = o.optLong("position");
            e.updated = o.optLong("updated");
            e.art = o.isNull("art") ? null : o.optString("art");
            e.image = o.isNull("image") ? null : o.optString("image");
            e.link = o.isNull("link") ? null : o.optString("link");
            e.series = o.optString("series");
            // Titles looked up before series were kept are looked up once more, to find theirs.
            e.lookedUp = o.optBoolean("lookedUp") && o.has("series");
            return e;
        }
    }

    private WatchHistory() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("watch_history", Context.MODE_PRIVATE);
    }

    static synchronized List<Entry> load(Context c) {
        List<Entry> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("entries", "[]"));
            for (int i = 0; i < a.length(); i++) list.add(Entry.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
            // Corrupt data: start over.
        }
        return list;
    }

    private static void save(Context c, List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) a.put(e.toJson());
        } catch (JSONException ignored) {
            return;
        }
        prefs(c).edit().putString("entries", a.toString()).apply();
    }

    /** Saves what a lookup found for a title, keeping any progress recorded while it ran. */
    static synchronized void update(Context c, Entry looked) {
        List<Entry> list = load(c);
        for (Entry e : list) {
            if (e.key().equals(looked.key())) e.copyLookup(looked);
        }
        dropOlderEpisodes(list); // its series may only be known now
        save(c, list);
    }

    /** Saves what {@code controller}'s app is playing right now, if it looks like a film or episode. */
    static synchronized void record(Context c, MediaController controller) {
        MediaMetadata m = controller.getMetadata();
        PlaybackState s = controller.getPlaybackState();
        if (m == null || s == null) return;

        String pkg = controller.getPackageName();
        String title = firstText(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String mediaId = firstText(m, MediaMetadata.METADATA_KEY_MEDIA_ID);
        long duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION)); // -1/0 = unknown
        long position = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }

        // Needs something to identify it (Prime Video sends no title, only its ID), and to look
        // like a film or episode that has really been watched.
        if (title.isEmpty() && mediaId.isEmpty()) return;
        if (duration > 0 ? duration < MIN_DURATION_MS || position < MIN_WATCHED_MS
                : position < MIN_WATCHED_NO_LENGTH_MS) return;

        Entry e = new Entry();
        e.pkg = pkg;
        e.title = title;
        e.subtitle = firstText(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
        if (e.subtitle.equalsIgnoreCase(appName(c, pkg))) e.subtitle = ""; // e.g. RTVE sends "RTVE Play"
        e.mediaId = mediaId;
        e.duration = duration;
        e.position = duration > 0 ? Math.min(position, duration) : position;
        e.updated = System.currentTimeMillis();

        List<Entry> list = load(c);
        for (Entry old : list) {
            if (old.key().equals(e.key())) {
                // Same title again: keep what was already found/saved for it. Some apps
                // (Atresplayer) drop the length now and then, so keep a known one.
                if (e.duration == 0) e.duration = old.duration;
                e.art = old.art;
                e.copyLookup(old);
            }
        }
        if (!e.lookedUp) joinSeries(e, list);
        list.removeIf(old -> old.key().equals(e.key()));
        if (e.art == null) e.art = saveArt(c, m, e.key());
        list.add(0, e);
        dropOlderEpisodes(list);
        trim(list);
        save(c, list);
    }

    /**
     * If {@code e} is another episode of a series already in {@code list} (the app sends the
     * series' name), it takes that series' picture and link right away, with no lookup needed.
     */
    static void joinSeries(Entry e, List<Entry> list) {
        for (Entry old : list) {
            if (old.pkg.equals(e.pkg) && !old.series.isEmpty()
                    && (old.series.equalsIgnoreCase(e.title) || old.series.equalsIgnoreCase(e.subtitle))) {
                e.copyLookup(old);
                return;
            }
        }
    }

    /** Keeps only the newest title of each series (the list is newest first), so older episodes go. */
    static void dropOlderEpisodes(List<Entry> list) {
        for (int i = 0; i < list.size(); i++) {
            for (int j = list.size() - 1; j > i; j--) {
                Entry older = list.get(j);
                if (!older.sameSeries(list.get(i))) continue;
                list.remove(j);
                if (older.art != null) new File(older.art).delete();
            }
        }
    }

    /** Keeps at most MAX titles. Those watched to the end are hidden anyway, so the oldest of those goes first. */
    static void trim(List<Entry> list) {
        while (list.size() > MAX) {
            int drop = list.size() - 1;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).finished()) {
                    drop = i;
                    break;
                }
            }
            Entry gone = list.remove(drop);
            if (gone.art != null) new File(gone.art).delete();
        }
    }

    private static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static String firstText(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence t = m.getText(k);
            if (!TextUtils.isEmpty(t)) return t.toString().trim();
        }
        return "";
    }

    /** Keeps a small copy of the app's artwork for the card, in case JustWatch has no image. */
    private static String saveArt(Context c, MediaMetadata m, String key) {
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) return null;

        int width = Math.min(400, art.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(art, width, art.getHeight() * width / art.getWidth(), true);
        File dir = new File(c.getFilesDir(), "art");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File file = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return file.getAbsolutePath();
        } catch (IOException ex) {
            return null;
        }
    }
}
