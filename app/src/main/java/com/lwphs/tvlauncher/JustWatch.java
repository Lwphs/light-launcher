package com.lwphs.tvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Title search through JustWatch's (unofficial) GraphQL API, the one justwatch.com uses.
 * It can change or stop working without notice.
 */
final class JustWatch {

    private static final String API = "https://apis.justwatch.com/graphql";
    private static final String IMAGES = "https://images.justwatch.com";
    private static final String COUNTRY = "ES";
    private static final String LANGUAGE = "es";

    /** A streaming service: JustWatch short name, its Android TV app, display name. */
    static final class Service {
        final String shortName, pkg, name;

        Service(String shortName, String pkg, String name) {
            this.shortName = shortName;
            this.pkg = pkg;
            this.name = name;
        }
    }

    // Subscription plans only: Movistar is the basic plan (not Ficción Total),
    // Prime Video is Prime itself (no channels, no buy/rent store).
    static final Service[] SERVICES = {
            new Service("nfx", "com.netflix.ninja", "Netflix"),
            new Service("mxx", "com.wbd.stream", "HBO Max"),
            new Service("dnp", "com.disney.disneyplus", "Disney+"),
            new Service("prv", "com.amazon.amazonvideo.livingroom", "Prime Video"),
            new Service("mp9", "com.movistarplus.androidtv", "Movistar Plus+"),
            new Service("atr", "com.antena3.atresplayer.tv", "Atresplayer"),
            new Service("rtv", "com.rtve.androidtv", "RTVE Play"),
            new Service("cru", "com.crunchyroll.crunchyroid", "Crunchyroll"),
            new Service("atp", "com.apple.atve.androidtv.appletv", "Apple TV"),
            new Service("fil", "com.filmin.androidtv", "Filmin"), // TV app; com.filmin.filmin is the phone app
            new Service("sst", "com.skyshowtime.skyshowtime.google", "SkyShowtime"),
            new Service("mbi", "com.mubi", "MUBI"),
    };

    static final class Offer {
        final Service service;
        final String url;

        Offer(Service service, String url) {
            this.service = service;
            this.url = url;
        }
    }

    static final class Title {
        final String name;
        final int year;
        final String posterUrl;   // null when JustWatch has no poster
        final String backdropUrl; // wide image; null when there is none
        final List<Offer> offers = new ArrayList<>();

        Title(String name, int year, String posterUrl, String backdropUrl) {
            this.name = name;
            this.year = year;
            this.posterUrl = posterUrl;
            this.backdropUrl = backdropUrl;
        }
    }

    private static final String QUERY =
            "query S($filter: TitleFilter!, $country: Country!, $language: Language!, $first: Int!, $pk: [String!]) {"
                    + " popularTitles(country: $country, filter: $filter, first: $first) { edges { node {"
                    + " content(country: $country, language: $language) { title originalReleaseYear posterUrl backdrops { backdropUrl } }"
                    + " offers(country: $country, platform: WEB, filter: { monetizationTypes: [FLATRATE], packages: $pk }) {"
                    + " standardWebURL package { shortName } } } } } }";

    private JustWatch() { }

    /** Titles matching {@code query} that are included in a subscription to one of {@code services}. */
    static List<Title> search(String query, List<Service> services, int limit) throws IOException, JSONException {
        JSONArray shortNames = new JSONArray();
        for (Service s : services) shortNames.put(s.shortName);

        JSONObject filter = new JSONObject()
                .put("searchQuery", query)
                .put("packages", shortNames)
                .put("monetizationTypes", new JSONArray().put("FLATRATE"));
        JSONObject variables = new JSONObject()
                .put("filter", filter)
                .put("pk", shortNames)
                .put("country", COUNTRY)
                .put("language", LANGUAGE)
                .put("first", limit);
        JSONObject body = new JSONObject().put("query", QUERY).put("variables", variables);

        JSONObject response = new JSONObject(post(body.toString()));
        JSONArray edges = response.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");

        List<Title> titles = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.getJSONObject(i).getJSONObject("node");
            JSONObject content = node.getJSONObject("content");
            String poster = content.optString("posterUrl", "");
            JSONArray backdrops = content.optJSONArray("backdrops");
            String backdrop = backdrops != null && backdrops.length() > 0
                    ? backdrops.getJSONObject(0).optString("backdropUrl", "") : "";
            Title title = new Title(
                    content.getString("title"),
                    content.optInt("originalReleaseYear", 0),
                    imageUrl(poster, "s332"),
                    imageUrl(backdrop, "s640"));

            // One offer per service (JustWatch lists each quality separately), in SERVICES order.
            JSONArray offers = node.getJSONArray("offers");
            for (Service s : services) {
                for (int j = 0; j < offers.length(); j++) {
                    JSONObject offer = offers.getJSONObject(j);
                    if (s.shortName.equals(offer.getJSONObject("package").getString("shortName"))) {
                        title.offers.add(new Offer(s, offer.getString("standardWebURL")));
                        break;
                    }
                }
            }
            if (!title.offers.isEmpty()) titles.add(title);
        }
        return titles;
    }

    /**
     * Full image URL for a JustWatch image path, or null. The path must start with "/" so it can
     * only ever point at JustWatch's own image server (no "@other-host" tricks).
     */
    private static String imageUrl(String path, String profile) {
        if (!path.startsWith("/")) return null;
        return IMAGES + path.replace("{profile}", profile).replace("{format}", "webp");
    }

    /** The service whose TV app is {@code pkg}, or null if it isn't one of ours. */
    static Service serviceForPackage(String pkg) {
        for (Service s : SERVICES) if (s.pkg.equals(pkg)) return s;
        return null;
    }

    /** Best match for {@code name} included in {@code service}'s subscription, or null. */
    static Title find(String name, Service service) throws IOException, JSONException {
        List<Title> found = search(name, Collections.singletonList(service), 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String post(String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                return new String(Net.readAll(in, Net.MAX_API_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
