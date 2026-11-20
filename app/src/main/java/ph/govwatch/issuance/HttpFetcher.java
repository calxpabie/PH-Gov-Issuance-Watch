package ph.govwatch.issuance;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads a page directly from the phone, like a browser would (first attempt). */
final class HttpFetcher {

    static final class Result {
        int code;
        String body;
        String error;
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 6;
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private HttpFetcher() {
    }

    static Result fetch(String startUrl) {
        Result r = new Result();
        String url = startUrl;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                try {
                    c.setInstanceFollowRedirects(false); // followed by hand so http -> https works
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", USER_AGENT);
                    c.setRequestProperty("Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    c.setRequestProperty("Accept-Language", "en-PH,en;q=0.9");
                    r.code = c.getResponseCode();
                    if (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307 || r.code == 308) {
                        String loc = c.getHeaderField("Location");
                        if (loc == null || loc.isEmpty()) {
                            r.error = "Redirect without a location";
                            return r;
                        }
                        url = new URI(url).resolve(loc).toString();
                        continue;
                    }
                    InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) {
                        r.body = read(in, charsetOf(c.getContentType()));
                    }
                    return r;
                } finally {
                    c.disconnect();
                }
            }
            r.error = "Too many redirects";
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
        return r;
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    // fall through to UTF-8
                }
            }
        }
        return Charset.forName("UTF-8");
    }

    private static String read(InputStream in, Charset cs) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    break;
                }
            }
            return new String(out.toByteArray(), cs);
        } finally {
            in.close();
        }
    }
}
