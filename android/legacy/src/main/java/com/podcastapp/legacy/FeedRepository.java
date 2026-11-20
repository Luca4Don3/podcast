package com.podcastapp.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/** Synchronous, stateless access to public podcast feeds. Call on a worker thread. */
public final class FeedRepository {
    /** Opens an absolute HTTP(S) RSS/Atom URL and returns at most 200 playable episodes. */
    public Feed openFeed(String feedUrl, Cancellation cancellation) throws IOException {
        checkCancelled(cancellation);
        URI url = FeedHttp.resolve(null, feedUrl);
        if (url == null) {
            throw new IOException("请输入不含账号信息的 HTTP 或 HTTPS 订阅地址");
        }
        return FeedHttp.open(url, cancellation);
    }

    /** Reads a local OPML stream without fetching feeds. The caller owns and closes the stream. */
    public List<Subscription> importOpml(InputStream stream, Cancellation cancellation)
            throws IOException {
        if (stream == null) {
            throw new IOException("无法打开 OPML 文件");
        }
        return FeedXml.readOpml(stream, cancellation);
    }

    public static boolean isHttpUrl(String value) {
        return FeedHttp.resolve(null, value) != null;
    }

    public static final class Feed {
        public final String title;
        /** Effective URL after safe redirects. */
        public final String url;
        public final List<Episode> episodes;

        Feed(String title, String url, List<Episode> episodes) {
            this.title = title;
            this.url = url;
            this.episodes = Collections.unmodifiableList(episodes);
        }
    }

    public static final class Episode {
        public final String title;
        public final String mediaUrl;
        /** Empty if omitted by the feed. */
        public final String mimeType;
        /** -1 if the size was omitted or invalid. */
        public final long lengthBytes;
        public final String id;

        Episode(String title, String mediaUrl, String mimeType, long lengthBytes, String id) {
            this.title = title;
            this.mediaUrl = mediaUrl;
            this.mimeType = mimeType;
            this.lengthBytes = lengthBytes;
            this.id = id;
        }
    }

    public static final class Subscription {
        public final String title;
        public final String url;

        Subscription(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    /** One token per invocation. Cancelling does not block the caller on network I/O. */
    public static final class Cancellation {
        private volatile boolean cancelled;
        private HttpURLConnection active;

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            final HttpURLConnection connection;
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                connection = active;
            }
            if (connection != null) {
                Thread worker = new Thread(new Runnable() {
                    @Override public void run() {
                        FeedHttp.disconnect(connection);
                    }
                }, "feed-cancel");
                worker.setDaemon(true);
                worker.start();
            }
        }

        synchronized void attach(HttpURLConnection connection) throws IOException {
            checkCancelled(this);
            if (active != null) {
                throw new IOException("取消令牌不能用于多个并行请求");
            }
            active = connection;
        }

        synchronized void detach(HttpURLConnection connection) {
            if (active == connection) {
                active = null;
            }
        }
    }

    static void checkCancelled(Cancellation cancellation) throws InterruptedIOException {
        if ((cancellation != null && cancellation.isCancelled())
                || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("操作已取消");
        }
    }
}
