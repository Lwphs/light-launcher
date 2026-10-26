package com.lwphs.tvlauncher;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows what other apps are playing (their media sessions) and records it in {@link WatchHistory}
 * for the "Continue watching" row. Android only lets a notification listener see other apps' media
 * sessions, so this needs notification access, granted once with:
 * adb shell cmd notification allow_listener com.lwphs.tvlauncher/com.lwphs.tvlauncher.WatchTracker
 * Notifications themselves are not read.
 */
public class WatchTracker extends NotificationListenerService {

    // Music apps: songs and podcasts aren't "watching".
    private static final Set<String> IGNORED = Set.of("com.spotify.tv.android", "com.google.android.youtube.tvmusic");

    private MediaSessionManager sessions;
    private final Map<MediaSession.Token, MediaController.Callback> followed = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener onSessionsChanged = this::follow;

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, WatchTracker.class);
        sessions.addOnActiveSessionsChangedListener(onSessionsChanged, me);
        follow(sessions.getActiveSessions(me));
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(onSessionsChanged);
    }

    private void follow(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController controller : controllers) {
            MediaSession.Token token = controller.getSessionToken();
            if (IGNORED.contains(controller.getPackageName()) || followed.containsKey(token)) continue;

            MediaController.Callback callback = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    WatchHistory.record(WatchTracker.this, controller);
                }

                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    WatchHistory.record(WatchTracker.this, controller);
                }

                @Override
                public void onSessionDestroyed() {
                    followed.remove(token);
                }
            };
            controller.registerCallback(callback);
            followed.put(token, callback);
            WatchHistory.record(this, controller);
        }
    }
}
