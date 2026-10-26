package com.lwphs.tvlauncher;

import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filmin's TV app ignores www.filmin.es links (what JustWatch gives) and only opens a title from
 * "https://androidtv.filmin.com/es/section=detail&id=<media id>". The media ID isn't in the web
 * link, but the title's public page has it in its picture path (".../images/es/media/4937/...").
 */
final class Filmin {

    private static final Pattern MEDIA_ID = Pattern.compile("property=\"og:image\" content=\"[^\"]*/media/(\\d+)/");

    private Filmin() { }

    /** The TV app link for a www.filmin.es title page, or null if it can't be worked out. */
    static String tvLink(String webUrl) throws IOException {
        Uri u = Uri.parse(webUrl);
        if (!"https".equals(u.getScheme()) || !"www.filmin.es".equals(u.getHost())) return null;
        HttpURLConnection conn = (HttpURLConnection) new URL(webUrl).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return null;
            String html;
            try (InputStream in = conn.getInputStream()) {
                html = new String(Net.readAll(in, Net.MAX_PAGE_BYTES), StandardCharsets.UTF_8);
            }
            Matcher id = MEDIA_ID.matcher(html);
            return id.find() ? "https://androidtv.filmin.com/es/section=detail&id=" + id.group(1) : null;
        } finally {
            conn.disconnect();
        }
    }
}
