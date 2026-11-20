#include "feed.h"
#include "player.h"

#include <stdio.h>
#include <string.h>

#define CHECK(cond) do { if (!(cond)) { g_printerr("%s:%d: %s\n", __func__, __LINE__, #cond); return FALSE; } } while (0)

static GPtrArray *parse(const char *text, const char *base, GError **error)
{
    return feed_parse((const guint8 *)text, strlen(text), base, error);
}

static gboolean test_urls(void)
{
    g_autofree char *url = http_url("HTTPS://example.org/audio%20file.ogg?p=1#marker");
    CHECK(g_strcmp0(url, "https://example.org/audio%20file.ogg?p=1") == 0);
    CHECK(!http_url("https://name:pass@example.org/a"));
    CHECK(!http_url("https://@example.org/a"));
    CHECK(!http_url("https:///no-host"));
    CHECK(!http_url("https://example.org/a\nb"));
    CHECK(!http_url("file:///tmp/audio.wav"));
    CHECK(!http_url("ftp://example.org/a"));
    CHECK(!http_url("https://example.org/a b"));
    return TRUE;
}

static gboolean test_feeds(void)
{
    const char *rss = "<rss version='2.0'><channel>"
        "<item><enclosure url='file:///tmp/a.wav'/></item>"
        "<item><title>新闻 &amp; updates</title><enclosure url='first.png' type='image/png'/>"
        "<enclosure url='first.mp3' type='audio/mpeg'/></item></channel></rss>";
    g_autoptr(GError) error = NULL;
    g_autoptr(GPtrArray) items = parse(rss, "https://example.org/rss/feed.xml", &error);
    CHECK(!error && items && items->len == 1);
    FeedItem *item = g_ptr_array_index(items, 0);
    CHECK(item->kind == FEED_AUDIO && !strcmp(item->title, "新闻 & updates"));
    CHECK(!strcmp(item->url, "https://example.org/rss/first.mp3"));

    g_clear_pointer(&items, g_ptr_array_unref);
    items = parse("<feed xmlns='http://www.w3.org/2005/Atom' xml:base='https://example.org/'><entry>"
                  "<link rel='alternate' href='post.html'/><link rel='enclosure' href='file.ogg'/>"
                  "</entry></feed>", NULL, &error);
    CHECK(!error && items && items->len == 1);
    item = g_ptr_array_index(items, 0);
    CHECK(item->kind == FEED_AUDIO && !strcmp(item->url, "https://example.org/file.ogg"));
    g_clear_pointer(&items, g_ptr_array_unref);
    items = parse("<opml version='2.0'><body><outline text='Folder'><outline text='Episode feed'"
                  " xmlUrl='https://example.org/feed.xml'/><outline xmlUrl='https://user@example.org/feed.xml'/>"
                  "</outline></body></opml>", NULL, &error);
    CHECK(!error && items && items->len == 1);
    item = g_ptr_array_index(items, 0);
    CHECK(item->kind == FEED_SUBSCRIPTION && !strcmp(item->title, "Episode feed"));
    return TRUE;
}

static gboolean test_bounds(void)
{
    g_autoptr(GError) error = NULL;
    g_autoptr(GPtrArray) items = parse("<!DOCTYPE rss [<!ENTITY ext SYSTEM 'file:///dev/zero'>]>"
                                      "<rss><channel><item><title>&ext;</title></item></channel></rss>", NULL, &error);
    CHECK(!items && g_error_matches(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA));
    g_clear_error(&error);
    items = parse("<rss><channel></rss>", NULL, &error);
    CHECK(!items && g_error_matches(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA));
    g_clear_error(&error);
    items = feed_parse((const guint8 *)"<rss/>", FEED_MAX_BYTES + 1, NULL, &error);
    CHECK(!items && g_error_matches(error, G_IO_ERROR, G_IO_ERROR_INVALID_DATA));
    g_clear_error(&error);
    g_autoptr(GString) many = g_string_new("<opml><body>");
    for (guint i = 0; i < FEED_MAX_ITEMS + 1; ++i)
        g_string_append(many, "<outline xmlUrl='https://example.org/rss' />");
    g_string_append(many, "</body></opml>");
    items = parse(many->str, NULL, &error);
    CHECK(items && items->len == FEED_MAX_ITEMS && !error);

    g_autoptr(GCancellable) cancel = g_cancellable_new();
    g_cancellable_cancel(cancel);
    g_clear_pointer(&items, g_ptr_array_unref);
    items = feed_import("https://example.org/not-contacted", FALSE, cancel, &error);
    CHECK(!items && g_error_matches(error, G_IO_ERROR, G_IO_ERROR_CANCELLED));
    return TRUE;
}

static GBytes *wav_bytes(void)
{
    static const guint8 header[] = {
        'R','I','F','F',0x24,0x7d,0,0,'W','A','V','E',
        'f','m','t',' ',16,0,0,0,1,0,1,0,0x40,0x1f,0,0,0x80,0x3e,0,0,2,0,16,0,
        'd','a','t','a',0,0x7d,0,0
    }; /* PCM, mono, S16LE, 8000 Hz, 2 seconds. */
    guint8 *wav = g_malloc0(sizeof header + 32000);
    memcpy(wav, header, sizeof header);
    return g_bytes_new_take(wav, sizeof header + 32000);
}

G_DEFINE_AUTOPTR_CLEANUP_FUNC(FILE, fclose)

static gboolean test_local_player(void)
{
    g_autoptr(FILE) file = tmpfile();
    CHECK(file);
    g_autoptr(GBytes) wav = wav_bytes();
    gsize bytes;
    const void *audio = g_bytes_get_data(wav, &bytes);
    CHECK(fwrite(audio, 1, bytes, file) == bytes && fflush(file) == 0);
    char path[64];
    g_snprintf(path, sizeof path, "/proc/self/fd/%d", fileno(file));
    GstElement *pipe = player_new(path, FALSE, TRUE);
    CHECK(pipe);
    gboolean ready = gst_element_set_state(pipe, GST_STATE_PAUSED) != GST_STATE_CHANGE_FAILURE
        && gst_element_get_state(pipe, NULL, NULL, 8 * GST_SECOND) == GST_STATE_CHANGE_SUCCESS;
    gint64 duration = 0;
    gboolean timed = ready && gst_element_query_duration(pipe, GST_FORMAT_TIME, &duration);
    gboolean seeked = timed && gst_element_seek_simple(pipe, GST_FORMAT_TIME, GST_SEEK_FLAG_FLUSH, GST_SECOND);
    gboolean played = seeked && gst_element_set_state(pipe, GST_STATE_PLAYING) != GST_STATE_CHANGE_FAILURE;
    g_autoptr(GstBus) bus = gst_element_get_bus(pipe);
    g_autoptr(GstMessage) message = gst_bus_timed_pop_filtered(bus, played ? 7 * GST_SECOND : 0,
                                         GST_MESSAGE_ERROR | GST_MESSAGE_EOS);
    if (message && GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR) {
        g_autoptr(GError) error = NULL;
        gst_message_parse_error(message, &error, NULL);
        g_printerr("Local WAV smoke: %s\n", error->message);
    }
    player_free(pipe);
    CHECK(ready && timed && duration == 2 * GST_SECOND && seeked && played);
    CHECK(message && GST_MESSAGE_TYPE(message) == GST_MESSAGE_EOS);
    return TRUE;
}

int smoke_test(void)
{
    gboolean ok = test_urls() && test_feeds() && test_bounds() && test_local_player();
    g_print("Core smoke (URL, RSS/Atom/OPML, bounds, WAV pause/seek/EOS): %s\n", ok ? "PASS" : "FAIL");
    return ok ? 0 : 1;
}
