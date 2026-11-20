import com.podcastapp.legacy.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public final class LegacyFeedHostTest {
    private static final String MEDIA_QUERY = "token=fixture%2fValue%2B+keep&part=1&part=2&empty=";
    static int failures = 0;
    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }
    private static boolean isAnonymous(HttpExchange request) {
        return !request.getRequestHeaders().containsKey("Authorization")
                && !request.getRequestHeaders().containsKey("Proxy-Authorization")
                && !request.getRequestHeaders().containsKey("Cookie");
    }

    private static void sendXml(HttpExchange request, String type, String xml) throws IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        try {
            request.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            request.sendResponseHeaders(200, body.length);
            request.getResponseBody().write(body);
        } finally {
            request.close();
        }
    }

    private static void expectTlsRejection(FeedRepository repo, String address, String name)
            throws IOException {
        try {
            repo.openFeed(address, null);
            check(false, name);
        } catch (SSLHandshakeException expected) {
            check(true, name);
        }
    }

    private static void testHttpsAtom(FeedRepository repo, String fixtureKeyStore, String httpUrl)
            throws Exception {
        // Trust only this run's synthetic certificate on the host JVM, without changing
        // hostname verification. No Android TLS configuration is touched.
        char[] password = "changeit".toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = new FileInputStream(fixtureKeyStore)) {
            keys.load(input, password);
        }
        KeyManagerFactory keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManager.init(keys, password);
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManager.getKeyManagers(), null, null);
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("localhost", keys.getCertificate("localhost"));
        TrustManagerFactory trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManager.init(trusted);
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManager.getTrustManagers(), null);

        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        int port = https.getAddress().getPort();
        String base = "https://localhost:" + port;
        String atom = "<feed xmlns='http://www.w3.org/2005/Atom'><title>Atom Feed</title>"
                + "<entry><id>atom-1</id><title>Atom Episode</title>"
                + "<link rel='enclosure' type='audio/mpeg' href='audio/ep.mp3?"
                + MEDIA_QUERY.replace("&", "&amp;") + "'/></entry></feed>";
        AtomicBoolean directAnonymous = new AtomicBoolean();
        https.createContext("/shows/feed", request -> {
            directAnonymous.set(isAnonymous(request) && ("localhost:" + port).equals(
                    request.getRequestHeaders().getFirst("Host")));
            sendXml(request, "application/atom+xml", atom);
        });
        https.createContext("/downgrade", request -> {
            request.getResponseHeaders().set("Location", httpUrl);
            request.sendResponseHeaders(302, -1);
            request.close();
        });
        https.start();
        SSLSocketFactory defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        try {
            String feedUrl = base + "/shows/feed";
            expectTlsRejection(repo, feedUrl, "HTTPS rejects the untrusted chain");
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.getSocketFactory());
            FeedRepository.Feed result = repo.openFeed(feedUrl, null);
            check(feedUrl.equals(result.url) && "Atom Feed".equals(result.title)
                    && result.episodes.size() == 1, "single-step Atom import on explicit HTTPS port");
            check(directAnonymous.get(), "HTTPS uses original host and port without auth headers or cookies");
            check(result.episodes.size() == 1 && (base + "/shows/audio/ep.mp3?" + MEDIA_QUERY)
                    .equals(result.episodes.get(0).mediaUrl), "exact synthetic media token query retained");
            expectTlsRejection(repo, "https://127.0.0.1:" + port + "/shows/feed",
                    "trusted certificate with the wrong hostname is rejected");
            try {
                repo.openFeed(base + "/downgrade", null);
                check(false, "HTTPS redirect cannot downgrade to HTTP");
            } catch (IOException expected) {
                check(true, "HTTPS redirect cannot downgrade to HTTP");
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultFactory);
            https.stop(0);
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String feed = "<?xml version='1.0'?><rss version='2.0'><channel><title>Caf\u00e9 Feed</title>"
            + "<item><title>Ep 1</title><guid>ep-1</guid>"
            + "<enclosure url='http://127.0.0.1:" + server.getAddress().getPort() + "/a.mp3?"
            + MEDIA_QUERY.replace("&", "&amp;") + "' type='audio/mpeg'/></item>"
            + "</channel></rss>";
        AtomicBoolean rssAnonymous = new AtomicBoolean();
        server.createContext("/feed", request -> {
            rssAnonymous.set(isAnonymous(request));
            sendXml(request, "application/rss+xml", feed);
        });
        AtomicInteger authRequests = new AtomicInteger();
        AtomicBoolean challengeAnonymous = new AtomicBoolean(true);
        server.createContext("/unauthorized", request -> {
            authRequests.incrementAndGet();
            if (!isAnonymous(request)) challengeAnonymous.set(false);
            request.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"synthetic\"");
            request.sendResponseHeaders(401, -1);
            request.close();
        });
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
            check((base + "/a.mp3?" + MEDIA_QUERY).equals(ep.mediaUrl),
                    "absolute RSS enclosure retains the exact synthetic token query");
            check("audio/mpeg".equals(ep.mimeType), "mime type");
            check(rssAnonymous.get(), "single-step RSS import without auth headers or cookies");
            try {
                repo.openFeed(base + "/unauthorized", null);
                check(false, "401 surfaces without a credential retry");
            } catch (IOException expected) {
                check(expected.getMessage().endsWith("HTTP 401")
                        && authRequests.get() == 1 && challengeAnonymous.get(),
                        "401 surfaces without a credential retry");
            }

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
            testHttpsAtom(repo, args[0], base + "/feed");
        } finally { server.stop(0); }
        System.out.println(failures == 0 ? "Legacy feed host tests: ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
