import com.podcastapp.legacy.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class LegacyFeedHostTest {
    static int failures = 0;
    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String feed = "<?xml version='1.0'?><rss version='2.0'><channel><title>Caf\u00e9 Feed</title>"
            + "<item><title>Ep 1</title><guid>ep-1</guid>"
            + "<enclosure url='http://127.0.0.1:" + server.getAddress().getPort() + "/a.mp3' type='audio/mpeg'/></item>"
            + "</channel></rss>";
        server.createContext("/feed", e -> { byte[] b = feed.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=utf-8");
            e.sendResponseHeaders(200, b.length); e.getResponseBody().write(b); e.close(); });
        server.createContext("/redirect", e -> { e.getResponseHeaders().add("Location", "/feed");
            e.sendResponseHeaders(302, -1); e.close(); });
        server.createContext("/loop", e -> { e.getResponseHeaders().add("Location", "/loop");
            e.sendResponseHeaders(302, -1); e.close(); });
        server.createContext("/large", e -> { e.sendResponseHeaders(200, 3000000); e.close(); });
        server.start();
        FeedRepository repo = new FeedRepository();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            FeedRepository.Feed parsed = repo.openFeed(base + "/feed", null);
            check("Caf\u00e9 Feed".equals(parsed.title), "RSS title and UTF-8");
            check(parsed.episodes.size() == 1, "episode count");
            FeedRepository.Episode ep = parsed.episodes.get(0);
            check("ep-1".equals(ep.id) && "Ep 1".equals(ep.title), "episode identity");
            check((base + "/a.mp3").equals(ep.mediaUrl), "absolute enclosure URL");
            check("audio/mpeg".equals(ep.mimeType), "mime type");

            FeedRepository.Feed viaRedirect = repo.openFeed(base + "/redirect", null);
            check(viaRedirect.episodes.size() == 1, "same-host redirect");

            try { repo.openFeed(base + "/loop", null); check(false, "redirect loop rejected"); }
            catch (IOException expected) { check(true, "redirect loop rejected"); }
            try { repo.openFeed("file:///etc/passwd", null); check(false, "file URL rejected"); }
            catch (IOException expected) { check(true, "file URL rejected"); }
            try { repo.openFeed("http://u:p@" + base.substring(7) + "/feed", null); check(false, "userinfo rejected"); }
            catch (IOException expected) { check(true, "userinfo rejected"); }
            try { repo.openFeed(base + "/large", null); check(false, "size limit"); }
            catch (IOException expected) { check(true, "size limit"); }

            FeedRepository.Cancellation cancel = new FeedRepository.Cancellation();
            cancel.cancel();
            try { repo.openFeed(base + "/feed", cancel); check(false, "cancellation"); }
            catch (InterruptedIOException expected) { check(true, "cancellation"); }

            String opml = "<?xml version='1.0'?><opml version='2.0'><body>"
                + "<outline text='A' xmlUrl='" + base + "/feed'/>"
                + "<outline text='B' xmlUrl='" + base + "/redirect'/>"
                + "<outline text='no url'/></body></opml>";
            List<FeedRepository.Subscription> subs = repo.importOpml(
                new ByteArrayInputStream(opml.getBytes(StandardCharsets.UTF_8)), null);
            check(subs.size() == 2 && "A".equals(subs.get(0).title), "OPML subscriptions");
            String dtd = "<!DOCTYPE opml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><opml><body>"
                + "<outline text='&x;' xmlUrl='" + base + "/feed'/></body></opml>";
            try { repo.importOpml(new ByteArrayInputStream(dtd.getBytes(StandardCharsets.UTF_8)), null);
                check(false, "DTD rejected"); }
            catch (IOException expected) { check(true, "DTD rejected"); }
        } finally { server.stop(0); }
        System.out.println(failures == 0 ? "Legacy feed host tests: ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
