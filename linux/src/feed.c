#include "feed.h"

#include <curl/curl.h>
#include <errno.h>
#include <fcntl.h>
#include <glib/gstdio.h>
#include <libxml/parser.h>
#include <string.h>
#include <unistd.h>

#define ATOM "http://www.w3.org/2005/Atom"
#define FEED_REDIRECTS 5
#define FEED_TIMEOUT_MS 12000

static void item_free(gpointer ptr)
{
    FeedItem *item = ptr;
    g_free(item->title);
    g_free(item->url);
    g_free(item);
}

static gboolean clean_uri_text(const char *text)
{
    if (!text || !*text || strlen(text) > FEED_MAX_URL || !g_utf8_validate(text, -1, NULL))
        return FALSE;
    for (const guchar *p = (const guchar *)text; *p; ++p)
        if (*p <= 0x20 || *p == 0x7f || *p == '\\')
            return FALSE;
    return TRUE;
}

char *http_url(const char *text)
{
    if (!clean_uri_text(text))
        return NULL;
    g_autoptr(GUri) uri = g_uri_parse(text, G_URI_FLAGS_ENCODED, NULL);
    if (!uri || !g_uri_get_host(uri) || !*g_uri_get_host(uri) || g_uri_get_userinfo(uri))
        return NULL;
    const char *scheme = g_uri_get_scheme(uri);
    if (strcmp(scheme, "http") && strcmp(scheme, "https"))
        return NULL;
    if (g_uri_get_port(uri) == 0)
        return NULL;
    char *url = g_uri_to_string_partial(uri, G_URI_HIDE_FRAGMENT);
    if (strlen(url) <= FEED_MAX_URL)
        return url;
    g_free(url);
    return NULL;
}

static gboolean is_element(xmlNodePtr node, const char *name, const char *ns)
{
    return node && node->type == XML_ELEMENT_NODE && xmlStrEqual(node->name, BAD_CAST name)
        && (ns ? node->ns && xmlStrEqual(node->ns->href, BAD_CAST ns) : !node->ns);
}

static xmlNodePtr child(xmlNodePtr node, const char *name, const char *ns)
{
    for (xmlNodePtr cur = node ? node->children : NULL; cur; cur = cur->next)
        if (is_element(cur, name, ns))
            return cur;
    return NULL;
}

static char *resolve(xmlNodePtr node, const xmlChar *value)
{
    const char *text = (const char *)value;
    if (!clean_uri_text(text))
        return NULL;
    if (g_uri_peek_scheme(text))
        return http_url(text);

    xmlChar *node_base = xmlNodeGetBase(node->doc, node);
    g_autofree char *base = http_url((const char *)node_base);
    xmlFree(node_base);
    if (!base)
        return NULL;
    g_autofree char *absolute = g_uri_resolve_relative(base, text, G_URI_FLAGS_ENCODED, NULL);
    return http_url(absolute);
}

static gboolean add_item(GPtrArray *out, char *url, const xmlChar *title, FeedKind kind)
{
    if (!url)
        return FALSE;
    FeedItem *item = g_new0(FeedItem, 1);
    item->url = url;
    item->kind = kind;
    if (title && *title) {
        const char *start = (const char *)title;
        const char *end = start;
        for (guint n = 0; *end && n < 240; ++n)
            end = g_utf8_next_char(end);
        item->title = g_strstrip(g_strndup(start, (gsize)(end - start)));
    }
    if (!item->title || !*item->title) {
        g_free(item->title);
        item->title = g_strdup(kind == FEED_AUDIO ? "Untitled episode" : "Untitled subscription");
    }
    g_ptr_array_add(out, item);
    return TRUE;
}

static char *url_prop(xmlNodePtr node, const char *prop)
{
    xmlChar *raw = xmlGetNoNsProp(node, BAD_CAST prop);
    char *url = resolve(node, raw);
    xmlFree(raw);
    return url;
}

static gboolean audio_type(const xmlChar *type)
{
    return !type || !*type || g_ascii_strncasecmp((const char *)type, "audio/", 6) == 0
        || xmlStrEqual(type, BAD_CAST "application/ogg")
        || xmlStrEqual(type, BAD_CAST "application/octet-stream");
}

static void read_entries(GPtrArray *out, xmlNodePtr parent, gboolean atom)
{
    const char *ns = atom ? ATOM : NULL;
    for (xmlNodePtr entry = parent->children; entry && out->len < FEED_MAX_ITEMS; entry = entry->next) {
        if (!is_element(entry, atom ? "entry" : "item", ns))
            continue;
        for (xmlNodePtr link = entry->children; link; link = link->next) {
            if (!is_element(link, atom ? "link" : "enclosure", ns))
                continue;
            xmlChar *rel = atom ? xmlGetNoNsProp(link, BAD_CAST "rel") : NULL;
            xmlChar *type = xmlGetNoNsProp(link, BAD_CAST "type");
            gboolean valid = audio_type(type) && (!atom || xmlStrEqual(rel, BAD_CAST "enclosure"));
            xmlFree(rel);
            xmlFree(type);
            char *url = valid ? url_prop(link, atom ? "href" : "url") : NULL;
            if (url) {
                xmlNodePtr title_node = child(entry, "title", ns);
                xmlChar *title = title_node ? xmlNodeGetContent(title_node) : NULL;
                add_item(out, url, title, FEED_AUDIO);
                xmlFree(title);
                break;
            }
        }
    }
}

static void read_outlines(GPtrArray *out, xmlNodePtr parent, guint depth)
{
    if (depth > 32)
        return;
    for (xmlNodePtr cur = parent->children; cur && out->len < FEED_MAX_ITEMS; cur = cur->next) {
        if (!is_element(cur, "outline", NULL))
            continue;
        char *url = url_prop(cur, "xmlUrl");
        if (url) {
            xmlChar *label = xmlGetNoNsProp(cur, BAD_CAST "title");
            if (!label || !*label) {
                xmlFree(label);
                label = xmlGetNoNsProp(cur, BAD_CAST "text");
            }
            add_item(out, url, label, FEED_SUBSCRIPTION);
            xmlFree(label);
        }
        read_outlines(out, cur, depth + 1);
    }
}

GPtrArray *feed_parse(const guint8 *data, gsize size, const char *base, GError **error)
{
    if (!data || size == 0 || size > FEED_MAX_BYTES) {
        g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA, "Feed must contain 1 byte to 2 MiB of XML.");
        return NULL;
    }
    /* No DTD loading, entity substitution, XInclude, recovery, or XML_PARSE_HUGE. */
    xmlDocPtr doc = xmlReadMemory((const char *)data, (int)size, base, NULL,
                                 XML_PARSE_NONET | XML_PARSE_NOERROR | XML_PARSE_NOWARNING);
    if (!doc || doc->intSubset || doc->extSubset) {
        xmlFreeDoc(doc);
        g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA, "Invalid XML or a prohibited DTD.");
        return NULL;
    }
    GPtrArray *out = g_ptr_array_new_with_free_func(item_free);
    xmlNodePtr root = xmlDocGetRootElement(doc);
    xmlNodePtr section;
    if (is_element(root, "rss", NULL) && (section = child(root, "channel", NULL)))
        read_entries(out, section, FALSE);
    else if (is_element(root, "feed", ATOM))
        read_entries(out, root, TRUE);
    else if (is_element(root, "opml", NULL) && (section = child(root, "body", NULL)))
        read_outlines(out, section, 1);
    else {
        g_clear_pointer(&out, g_ptr_array_unref);
        g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA, "Expected RSS 2.0, Atom, or OPML.");
    }
    xmlFreeDoc(doc);
    return out;
}

static size_t add_bytes(char *ptr, size_t size, size_t n, void *userdata)
{
    GByteArray *body = userdata;
    if (size && n > (FEED_MAX_BYTES - body->len) / size)
        return 0;
    size_t bytes = size * n;
    if (bytes)
        g_byte_array_append(body, (const guint8 *)ptr, (guint)bytes);
    return bytes;
}

static int progress(void *data, curl_off_t total, curl_off_t received, curl_off_t up, curl_off_t sent)
{
    (void)total; (void)received; (void)up; (void)sent;
    return g_cancellable_is_cancelled(data);
}

static GPtrArray *fetch(const char *location, GCancellable *cancel, GError **error)
{
    g_autofree char *url = http_url(location);
    if (!url) {
        g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_ARGUMENT, "Enter a credential-free HTTP(S) URL.");
        return NULL;
    }
    const gboolean require_https = g_str_has_prefix(url, "https:");
    CURL *curl = curl_easy_init();
    if (!curl) {
        g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_FAILED, "Could not initialize the HTTP client.");
        return NULL;
    }
    g_autoptr(GByteArray) body = g_byte_array_new();
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, require_https ? "https" : "http,https");
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "podcast-linux/0.1");
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT_MS, 4000L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 1L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 2L);
    curl_easy_setopt(curl, CURLOPT_NETRC, (long)CURL_NETRC_IGNORED);
    curl_easy_setopt(curl, CURLOPT_MAXFILESIZE_LARGE, (curl_off_t)FEED_MAX_BYTES);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, add_bytes);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, body);
    curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, progress);
    curl_easy_setopt(curl, CURLOPT_XFERINFODATA, cancel);
    curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);

    GPtrArray *out = NULL;
    gint64 deadline = g_get_monotonic_time() + FEED_TIMEOUT_MS * G_TIME_SPAN_MILLISECOND;
    for (int hop = 0; hop <= FEED_REDIRECTS; ++hop) {
        if (g_cancellable_set_error_if_cancelled(cancel, error))
            break;
        gint64 remaining_ms = (deadline - g_get_monotonic_time()) / G_TIME_SPAN_MILLISECOND;
        if (remaining_ms <= 0) {
            g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_TIMED_OUT, "Feed request timed out.");
            break;
        }
        g_byte_array_set_size(body, 0);
        curl_easy_setopt(curl, CURLOPT_URL, url);
        curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, (long)remaining_ms);
        CURLcode result = curl_easy_perform(curl);
        if (g_cancellable_set_error_if_cancelled(cancel, error))
            break;
        if (result != CURLE_OK) {
            g_set_error(error, G_IO_ERROR, G_IO_ERROR_FAILED, "Feed request failed: %s%s",
                        curl_easy_strerror(result), result == CURLE_WRITE_ERROR || result == CURLE_FILESIZE_EXCEEDED
                        ? " (2 MiB limit)" : "");
            break;
        }
        long code = 0;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &code);
        if (code >= 200 && code < 300) {
            out = feed_parse(body->data, body->len, url, error);
            break;
        }
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            char *target = NULL;
            curl_easy_getinfo(curl, CURLINFO_REDIRECT_URL, &target);
            char *next = http_url(target);
            if (hop == FEED_REDIRECTS || !next || (require_https && !g_str_has_prefix(next, "https:"))) {
                g_free(next);
                g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_FAILED, "Rejected feed redirect or redirect limit reached.");
                break;
            }
            g_free(url);
            url = next;
        } else {
            g_set_error(error, G_IO_ERROR, G_IO_ERROR_FAILED, "Feed server returned HTTP %ld.", code);
            break;
        }
    }
    curl_easy_cleanup(curl);
    return out;
}

GPtrArray *feed_import(const char *location, gboolean local, GCancellable *cancel, GError **error)
{
    if (!local)
        return fetch(location, cancel, error);

    int fd = g_open(location, O_RDONLY | O_NONBLOCK | O_CLOEXEC, 0);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || st.st_size > FEED_MAX_BYTES) {
        if (fd >= 0)
            close(fd);
        g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA, "Select a readable regular XML file of at most 2 MiB.");
        return NULL;
    }
    g_autoptr(GByteArray) body = g_byte_array_new();
    while (!g_cancellable_is_cancelled(cancel)) {
        guint8 chunk[8192];
        ssize_t n = read(fd, chunk, sizeof chunk);
        if (n < 0 && errno == EINTR)
            continue;
        if (n == 0)
            break;
        if (n < 0 || (gsize)n > FEED_MAX_BYTES - body->len) {
            close(fd);
            g_set_error_literal(error, G_IO_ERROR, G_IO_ERROR_FAILED, "Could not read XML file within 2 MiB.");
            return NULL;
        }
        g_byte_array_append(body, chunk, (guint)n);
    }
    close(fd);
    if (g_cancellable_set_error_if_cancelled(cancel, error))
        return NULL;
    return feed_parse(body->data, body->len, NULL, error);
}
