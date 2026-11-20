#ifndef PODCAST_PLAYER_H
#define PODCAST_PLAYER_H

#include <gst/gst.h>

/* uri_or_path is a validated HTTP(S) URL for remote, or a filesystem path. */
GstElement *player_new(const char *uri_or_path, gboolean remote, gboolean silent);
void player_free(GstElement *pipeline);
#endif
