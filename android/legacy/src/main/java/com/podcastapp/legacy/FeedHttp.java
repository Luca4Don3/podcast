package com.podcastapp.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

final class FeedHttp {
    static final int MAX_FEED_BYTES = 2 * 1024 * 1024;
    private static final int MAX_URL_LENGTH = 4096;
    private static final int MAX_REDIRECTS = 5;
    private static final int TIMEOUT_MS = 10000;
    private static final long TIME_BUDGET_NS = 40L * 1000 * 1000 * 1000;

    private FeedHttp() { }

    static URI resolve(URI base, String reference) {
        if (reference == null || reference.length() > MAX_URL_LENGTH) {
            return null;
        }
        reference = reference.trim();
        if (reference.length() == 0) {
            return null;
        }
        try {
            int fragment = reference.indexOf('#');
            if (fragment >= 0) {
                reference = reference.substring(0, fragment);
                if (reference.length() == 0) {
                    return null;
                }
            }
            URI url = new URI(reference);
            if (!url.isAbsolute() && base != null) {
                if (reference.charAt(0) == '?') {
                    // URI.resolve("?query") treats the base's final path segment as a directory.
                    String baseUrl = base.toString();
                    int query = baseUrl.indexOf('?');
                    url = new URI((query < 0 ? baseUrl : baseUrl.substring(0, query)) + reference);
                } else {
                    url = base.resolve(url);
                }
            }
            String scheme = url.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || url.getHost() == null || url.getRawUserInfo() != null
                    || url.getPort() == 0 || url.getPort() > 65535) {
                return null;
            }
            String ascii = url.toASCIIString();
            return ascii.length() <= MAX_URL_LENGTH ? new URI(ascii) : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    static URI redirect(URI current, String location) throws IOException {
        URI next = resolve(current, location);
        if (next == null) {
            throw new IOException("订阅重定向地址无效");
        }
        if ("https".equalsIgnoreCase(current.getScheme())
                && !"https".equalsIgnoreCase(next.getScheme())) {
            throw new IOException("拒绝从 HTTPS 重定向到 HTTP");
        }
        return next;
    }

    static FeedRepository.Feed open(URI url, FeedRepository.Cancellation cancellation)
            throws IOException {
        long deadline = System.nanoTime() + TIME_BUDGET_NS;
        Set<URI> visited = new HashSet<URI>();
        for (int redirects = 0; ; redirects++) {
            check(cancellation, deadline);
            if (!visited.add(url)) {
                throw new IOException("订阅地址出现重定向循环");
            }
            // API 14 has no per-connection cookie/authenticator settings. The standalone app
            // installs no Authenticator; fail closed if a process-wide cookie handler is present.
            if (CookieHandler.getDefault() != null) {
                throw new IOException("不能通过带有全局 Cookie 的网络客户端打开公开订阅");
            }
            HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection(Proxy.NO_PROXY);
            InputStream body = null;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
                // Explicit header avoids implicit transparent gzip (we bound both byte counts).
                connection.setRequestProperty("Accept-Encoding", "gzip");
                if (cancellation != null) {
                    cancellation.attach(connection);
                }
                check(cancellation, deadline);
                int status = connection.getResponseCode();
                check(cancellation, deadline);
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects == MAX_REDIRECTS) {
                        throw new IOException("订阅重定向次数过多");
                    }
                    url = redirect(url, connection.getHeaderField("Location"));
                    continue;
                }
                if (status < 200 || status >= 300 || status == 206) {
                    throw new IOException("订阅服务器返回 HTTP " + status);
                }
                if (connection.getContentLength() > MAX_FEED_BYTES) {
                    throw new IOException("订阅内容太大");
                }
                String encoding = connection.getContentEncoding();
                boolean gzip = "gzip".equalsIgnoreCase(encoding);
                if (!gzip && encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("不支持的订阅内容编码");
                }
                body = connection.getInputStream();
                body = new FeedInput(body, MAX_FEED_BYTES, cancellation, deadline);
                if (gzip) {
                    body = new GZIPInputStream(body);
                }
                return FeedXml.readFeed(body, url, cancellation, deadline);
            } catch (IOException e) {
                check(cancellation, deadline);
                throw e;
            } finally {
                try {
                    if (body != null) {
                        body.close();
                    }
                } catch (IOException ignored) {
                    // Closing a response must not replace the parsing/transport error.
                } finally {
                    if (cancellation != null) {
                        cancellation.detach(connection);
                    }
                    disconnect(connection);
                }
            }
        }
    }

    static void check(FeedRepository.Cancellation cancellation, long deadline) throws IOException {
        FeedRepository.checkCancelled(cancellation);
        if (deadline != 0 && System.nanoTime() - deadline >= 0) {
            throw new IOException("打开订阅超时");
        }
    }

    static void disconnect(HttpURLConnection connection) {
        connection.disconnect();
    }
}
