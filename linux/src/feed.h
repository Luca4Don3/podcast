#ifndef PODCAST_FEED_H
#define PODCAST_FEED_H

#include <gio/gio.h>

#define FEED_MAX_BYTES (2u * 1024u * 1024u)
#define FEED_MAX_ITEMS 200u
#define FEED_MAX_URL 4096u

typedef enum { FEED_AUDIO, FEED_SUBSCRIPTION } FeedKind;
typedef struct { char *title; char *url; FeedKind kind; } FeedItem;

char *http_url(const char *text);
GPtrArray *feed_parse(const guint8 *data, gsize size, const char *base, GError **error);
GPtrArray *feed_import(const char *location, gboolean local, GCancellable *cancel, GError **error);
#endif
