package com.lwphs.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads with limits, so a broken or hostile answer can't run the launcher out of memory or
 * make the TV fetch from somewhere unexpected (e.g. a device on the home network).
 */
final class Net {

    static final int MAX_API_BYTES = 1024 * 1024;       // JustWatch answers are ~35 KB
    static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;  // Prime Video title pages are ~1 MB
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_IMAGE_WIDTH = 640;     // largest shown (cards); bigger is decoded smaller
    private static final int TIMEOUT_MS = 8000;

    private Net() { }

    /** Only HTTPS pictures from JustWatch's and Amazon's image servers are loaded. */
    static boolean isAllowedImage(String url) {
        Uri u = Uri.parse(url);
        String host = u.getHost();
        return "https".equals(u.getScheme()) && host != null
                && (host.equals("images.justwatch.com") || host.endsWith(".media-amazon.com"));
    }

    /** Reads at most {@code max} bytes; more than that is treated as an error. */
    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        for (int n; (n = in.read(chunk)) != -1; ) {
            if (buffer.size() + n > max) throw new IOException("response larger than " + max + " bytes");
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /** Downloads an allowed image and decodes it no wider than needed; null if not allowed or broken. */
    static Bitmap image(String url) throws IOException {
        if (!isAllowedImage(url)) return null;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        byte[] data;
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                data = readAll(in, MAX_IMAGE_BYTES);
            }
        } finally {
            conn.disconnect();
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (opts.outWidth <= 0) return null;
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= MAX_IMAGE_WIDTH) sample *= 2;
        opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }
}
