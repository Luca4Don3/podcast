package com.podcastapp.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

final class FeedXml {
    static final int MAX_OPML_BYTES = 1024 * 1024;
    static final int MAX_EPISODES = 200;
    static final int MAX_SUBSCRIPTIONS = 200;
    static final int MAX_DEPTH = 48;
    private static final int MAX_TEXT = 512;
    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    private FeedXml() { }

    static FeedRepository.Feed readFeed(InputStream input, URI source,
            FeedRepository.Cancellation cancellation, long deadline) throws IOException {
        try {
            Cursor xml = new Cursor(input, FeedHttp.MAX_FEED_BYTES, cancellation, deadline);
            xml.root();
            String ns = xml.parser.getNamespace();
            String title;
            List<FeedRepository.Episode> episodes = new ArrayList<FeedRepository.Episode>();
            URI base = xml.base(source);
            if (xml.at(ns, "rss")) {
                boolean foundChannel = false;
                title = "";
                while (xml.child()) {
                    if (!foundChannel && xml.at(ns, "channel")) {
                        foundChannel = true;
                        title = readContainer(xml, ns, xml.base(base), false, episodes);
                    } else {
                        xml.skip();
                    }
                }
                if (!foundChannel) {
                    throw new IOException("RSS 缺少 channel 元素");
                }
            } else if (xml.at(ATOM_NS, "feed") || xml.at("", "feed")) {
                title = readContainer(xml, ns, base, true, episodes);
            } else {
                throw new IOException("不是 RSS 或 Atom 订阅");
            }
            xml.finish();
            return new FeedRepository.Feed(or(title, source.getHost()), source.toString(), episodes);
        } catch (XmlPullParserException e) {
            throw new IOException("无法解析订阅 XML");
        }
    }

    static List<FeedRepository.Subscription> readOpml(InputStream input,
            FeedRepository.Cancellation cancellation) throws IOException {
        try {
            Cursor xml = new Cursor(input, MAX_OPML_BYTES, cancellation, 0);
            xml.root();
            String ns = xml.parser.getNamespace();
            if (!xml.at(ns, "opml")) {
                throw new IOException("不是 OPML 文件");
            }
            URI base = xml.base(null);
            List<FeedRepository.Subscription> feeds = new ArrayList<FeedRepository.Subscription>();
            Set<URI> seen = new HashSet<URI>();
            boolean foundBody = false;
            while (xml.child()) {
                if (!foundBody && xml.at(ns, "body")) {
                    foundBody = true;
                    readOutlines(xml, ns, xml.base(base), feeds, seen);
                } else {
                    xml.skip();
                }
            }
            if (!foundBody) {
                throw new IOException("OPML 缺少 body 元素");
            }
            xml.finish();
            return Collections.unmodifiableList(feeds);
        } catch (XmlPullParserException e) {
            throw new IOException("无法解析 OPML 文件");
        }
    }

    private static void readOutlines(Cursor xml, String ns, URI base,
            List<FeedRepository.Subscription> feeds, Set<URI> seen)
            throws IOException, XmlPullParserException {
        int parentDepth = xml.parser.getDepth();
        while (xml.descendant(parentDepth)) {
            if (!xml.at(ns, "outline") || feeds.size() >= MAX_SUBSCRIPTIONS) continue;
            URI outlineBase = xml.base(base);
            URI url = FeedHttp.resolve(outlineBase, xml.attr("xmlUrl"));
            if (url != null && seen.add(url)) {
                String title = or(clipped(xml.attr("title")), clipped(xml.attr("text")));
                feeds.add(new FeedRepository.Subscription(or(title, url.getHost()), url.toString()));
            }
        }
    }

    private static String readContainer(Cursor xml, String ns, URI base, boolean atom,
            List<FeedRepository.Episode> episodes) throws IOException, XmlPullParserException {
        String title = "";
        while (xml.child()) {
            if (title.length() == 0 && xml.at(ns, "title")) {
                title = xml.text();
            } else if (episodes.size() < MAX_EPISODES && xml.at(ns, atom ? "entry" : "item")) {
                FeedRepository.Episode item = readEpisode(xml, ns, xml.base(base), atom);
                if (item != null) {
                    episodes.add(item);
                }
            } else {
                xml.skip();
            }
        }
        return title;
    }

    private static FeedRepository.Episode readEpisode(Cursor xml, String ns, URI base, boolean atom)
            throws IOException, XmlPullParserException {
        String title = "";
        String id = "";
        String media = null;
        String mime = "";
        long length = -1;
        int rank = 0;
        while (xml.child()) {
            if (title.length() == 0 && xml.at(ns, "title")) {
                title = xml.text();
            } else if (id.length() == 0 && xml.at(ns, atom ? "id" : "guid")) {
                id = xml.text();
            } else {
                if ((!atom && xml.at(ns, "enclosure"))
                        || (atom && xml.at(ns, "link") && "enclosure".equals(xml.attr("rel")))) {
                    String type = clipped(xml.attr("type"));
                    int priority = audioRank(type);
                    if (priority > rank) {
                        URI url = FeedHttp.resolve(xml.base(base), xml.attr(atom ? "href" : "url"));
                        if (url != null) {
                            media = url.toString();
                            mime = type;
                            length = byteLength(xml.attr("length"));
                            rank = priority;
                        }
                    }
                }
                xml.skip();
            }
        }
        return media == null ? null : new FeedRepository.Episode(
                or(title, "未命名剧集"), media, mime, length, or(id, media));
    }

    private static int audioRank(String type) {
        int end = type.indexOf(';');
        String mime = (end < 0 ? type : type.substring(0, end)).trim().toLowerCase(Locale.US);
        if (mime.startsWith("audio/") || mime.equals("application/ogg") || mime.equals("application/x-ogg")) {
            return 2;
        }
        return mime.length() == 0 || mime.equals("application/octet-stream") ? 1 : 0;
    }

    private static long byteLength(String value) {
        if (value == null || value.length() > 19 || value.length() == 0) {
            return -1;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return -1;
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String clipped(String value) {
        return value == null ? "" : value.substring(0, Math.min(MAX_TEXT, value.length())).trim();
    }

    private static String or(String value, String fallback) {
        return value.length() == 0 ? fallback : value;
    }

    private static final class Cursor {
        final XmlPullParser parser;
        private final FeedRepository.Cancellation cancellation;
        private final long deadline;

        Cursor(InputStream input, int limit, FeedRepository.Cancellation cancellation, long deadline)
                throws XmlPullParserException, IOException {
            this.cancellation = cancellation;
            this.deadline = deadline;
            FeedHttp.check(cancellation, deadline);
            parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            try {
                // Some Pull implementations (including kxml2) do not expose this feature;
                // nextToken() below rejects DOCDECL events regardless of parser support.
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            } catch (XmlPullParserException unsupported) {
                // Document declarations are still detected and rejected in next().
            }
            parser.setInput(new FeedInput(input, limit, cancellation, deadline), null);
        }

        boolean at(String ns, String name) {
            return name.equals(parser.getName()) && ns.equals(parser.getNamespace());
        }

        String attr(String name) {
            return parser.getAttributeValue("", name);
        }

        URI base(URI inherited) {
            String declared = parser.getAttributeValue(XML_NS, "base");
            return declared == null || declared.trim().length() == 0
                    ? inherited : FeedHttp.resolve(inherited, declared);
        }

        private int next() throws IOException, XmlPullParserException {
            FeedHttp.check(cancellation, deadline);
            // next() can hide a document type declaration; every traversal uses nextToken().
            int event = parser.nextToken();
            if (event == XmlPullParser.DOCDECL) {
                throw new IOException("XML 不允许文档类型声明 (DOCTYPE)");
            }
            if (event == XmlPullParser.ENTITY_REF && parser.getText() == null) {
                throw new IOException("XML 含有未定义的实体引用");
            }
            if (event == XmlPullParser.START_TAG && parser.getDepth() > MAX_DEPTH) {
                throw new IOException("XML 嵌套过深");
            }
            return event;
        }

        void root() throws IOException, XmlPullParserException {
            while (true) {
                int event = next();
                if (event == XmlPullParser.START_TAG) {
                    return;
                }
                if (event == XmlPullParser.END_DOCUMENT || !outsideRoot(event)) {
                    throw new IOException("XML 缺少根元素");
                }
            }
        }

        void finish() throws IOException, XmlPullParserException {
            int event;
            while ((event = next()) != XmlPullParser.END_DOCUMENT) {
                if (!outsideRoot(event)) {
                    throw new IOException("XML 根元素之后还有内容");
                }
            }
            FeedHttp.check(cancellation, deadline);
        }

        private boolean outsideRoot(int event) throws XmlPullParserException {
            return event == XmlPullParser.COMMENT || event == XmlPullParser.PROCESSING_INSTRUCTION
                    || event == XmlPullParser.IGNORABLE_WHITESPACE
                    || (event == XmlPullParser.TEXT && parser.isWhitespace());
        }

        // Visits every descendant START_TAG until the element at parentDepth closes.
        boolean descendant(int parentDepth) throws IOException, XmlPullParserException {
            while (true) {
                int event = next();
                if (event == XmlPullParser.END_DOCUMENT) {
                    throw new IOException("XML 元素未闭合");
                }
                if (event == XmlPullParser.START_TAG) return true;
                if (event == XmlPullParser.END_TAG && parser.getDepth() == parentDepth) return false;
            }
        }

        // Children are consumed in full by the caller before asking for the next child.
        boolean child() throws IOException, XmlPullParserException {
            int event;
            do {
                event = next();
                if (event == XmlPullParser.END_DOCUMENT) {
                    throw new IOException("XML 元素未闭合");
                }
            } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_TAG);
            return event == XmlPullParser.START_TAG;
        }

        void skip() throws IOException, XmlPullParserException {
            int depth = parser.getDepth();
            int event;
            do {
                event = next();
                if (event == XmlPullParser.END_DOCUMENT) {
                    throw new IOException("XML 元素未闭合");
                }
            } while (event != XmlPullParser.END_TAG || depth != parser.getDepth());
        }

        String text() throws IOException, XmlPullParserException {
            int depth = parser.getDepth();
            StringBuilder text = new StringBuilder();
            int[] range = new int[2];
            int event;
            do {
                event = next();
                if (event == XmlPullParser.END_DOCUMENT) {
                    throw new IOException("XML 元素未闭合");
                }
                if (text.length() < MAX_TEXT && (event == XmlPullParser.TEXT
                        || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF)) {
                    char[] chars = parser.getTextCharacters(range);
                    if (chars != null) {
                        text.append(chars, range[0], Math.min(range[1], MAX_TEXT - text.length()));
                    }
                }
            } while (event != XmlPullParser.END_TAG || depth != parser.getDepth());
            return text.toString().trim();
        }
    }
}
