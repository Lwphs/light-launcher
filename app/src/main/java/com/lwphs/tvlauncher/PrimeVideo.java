package com.lwphs.tvlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prime Video reports only its content ID ("amzn1.dv.gti.…") for what's playing, with no title.
 * The title's public web page gives the name and picture, and the ID makes a link to it in the app.
 */
final class PrimeVideo {

    static final String PACKAGE = "com.amazon.amazonvideo.livingroom";
    private static final String ID_PREFIX = "amzn1.dv.gti.";

    static final class Page {
        final String name, image; // image may be null

        Page(String name, String image) {
            this.name = name;
            this.image = image;
        }
    }

    private PrimeVideo() { }

    static boolean isContentId(String mediaId) {
        return mediaId.startsWith(ID_PREFIX);
    }

    /** Opens the title in the Prime Video app (same link form JustWatch uses). */
    static String link(String contentId) {
        return "https://app.primevideo.com/detail?gti=" + contentId;
    }

    /** Name and picture from the title's public page, or null if they can't be read. */
    static Page page(String contentId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://www.primevideo.com/-/es/detail/" + contentId + "/").openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept-Language", "es-ES");
            if (conn.getResponseCode() != 200) return null;
            String html;
            try (InputStream in = conn.getInputStream()) {
                html = new String(Net.readAll(in, Net.MAX_PAGE_BYTES), StandardCharsets.UTF_8);
            }
            Matcher title = Pattern.compile("<title>(?:Prime Video: )?([^<]+)</title>").matcher(html);
            if (!title.find()) return null;
            Matcher image = Pattern.compile("property=\"og:image\"[^>]*content=\"([^\"]+)\"").matcher(html);
            return new Page(unescape(title.group(1).trim()), image.find() ? image.group(1) : null);
        } finally {
            conn.disconnect();
        }
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }
}
