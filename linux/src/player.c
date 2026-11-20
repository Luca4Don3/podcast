#include "player.h"

#include <glib/gstdio.h>
#include <string.h>

/* Skip adaptive decoders that would fetch additional URLs from manifests. */
static gint select_decoder(GstElement *decode, GstPad *pad, GstCaps *caps,
                           GstElementFactory *factory, gpointer unused)
{
    (void)decode; (void)pad; (void)unused;
    if (gst_caps_get_size(caps) > 0) {
        const char *name = gst_structure_get_name(gst_caps_get_structure(caps, 0));
        if (g_str_has_prefix(name, "video/") || g_str_has_prefix(name, "image/")
            || !strcmp(name, "application/x-hls") || !strcmp(name, "application/dash+xml")
            || !strcmp(name, "application/vnd.ms-sstr+xml"))
            return 2; /* GST_AUTOPLUG_SELECT_SKIP (decodebin's signal-private enum). */
    }
    const char *klass = gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_KLASS);
    return klass && strstr(klass, "Adaptive") ? 2 : 0; /* GST_AUTOPLUG_SELECT_TRY */
}

static void audio_pad(GstElement *decode, GstPad *pad, gpointer data)
{
    (void)decode;
    GstElement *queue = data;
    g_autoptr(GstCaps) caps = gst_pad_get_current_caps(pad);
    if (!caps || gst_caps_get_size(caps) == 0
        || !gst_structure_has_name(gst_caps_get_structure(caps, 0), "audio/x-raw"))
        return;
    g_autoptr(GstPad) sink = gst_element_get_static_pad(queue, "sink");
    if (!gst_pad_is_linked(sink))
        (void)gst_pad_link(pad, sink);
}

static void no_more_pads(GstElement *decode, gpointer data)
{
    g_autoptr(GstPad) sink = gst_element_get_static_pad(GST_ELEMENT(data), "sink");
    if (!gst_pad_is_linked(sink))
        GST_ELEMENT_ERROR(decode, STREAM, WRONG_TYPE, ("No supported audio stream."), (NULL));
}

GstElement *player_new(const char *uri_or_path, gboolean remote, gboolean silent, GError **error)
{
    struct stat st;
    if (!remote && (g_stat(uri_or_path, &st) != 0 || !S_ISREG(st.st_mode))) {
        g_set_error_literal(error, GST_RESOURCE_ERROR, GST_RESOURCE_ERROR_OPEN_READ,
                            "Select a readable regular audio file.");
        return NULL;
    }

    GstElement *pipeline = gst_pipeline_new(NULL);
    const char *factories[] = { remote ? "souphttpsrc" : "filesrc", "decodebin", "queue",
                               "audioconvert", "audioresample", "volume", silent ? "fakesink" : "autoaudiosink" };
    GstElement *elements[G_N_ELEMENTS(factories)];
    for (guint i = 0; i < G_N_ELEMENTS(elements); ++i) {
        elements[i] = gst_element_factory_make(factories[i], i == 5 ? "playback-volume" : NULL);
        if (!elements[i]) {
            g_set_error(error, GST_CORE_ERROR, GST_CORE_ERROR_MISSING_PLUGIN,
                        "Missing GStreamer element '%s'. Install GStreamer base/good plugins.", factories[i]);
            gst_object_unref(pipeline);
            return NULL;
        }
        gst_bin_add(GST_BIN(pipeline), elements[i]);
    }
    GstElement *source = elements[0];
    GstElement *decode = elements[1];
    GstElement *queue = elements[2];
    g_object_set(source, "location", uri_or_path, NULL);
    /* Direct media only: do not let a publisher redirect playback to another URI. */
    if (remote)
        g_object_set(source, "ssl-strict", TRUE, "automatic-redirect", FALSE,
                     "timeout", 10u, "retries", 0, NULL);
    if (silent)
        g_object_set(elements[6], "sync", TRUE, NULL);
    if (!gst_element_link(source, decode)
        || !gst_element_link_many(queue, elements[3], elements[4], elements[5], elements[6], NULL)) {
        g_set_error_literal(error, GST_CORE_ERROR, GST_CORE_ERROR_NEGOTIATION,
                            "Could not link the GStreamer audio pipeline.");
        gst_object_unref(pipeline);
        return NULL;
    }
    g_signal_connect(decode, "autoplug-select", G_CALLBACK(select_decoder), NULL);
    g_signal_connect_object(decode, "pad-added", G_CALLBACK(audio_pad), queue, 0);
    g_signal_connect_object(decode, "no-more-pads", G_CALLBACK(no_more_pads), queue, 0);
    return pipeline;
}

void player_free(GstElement *pipeline)
{
    if (pipeline) {
        gst_element_set_state(pipeline, GST_STATE_NULL);
        gst_object_unref(pipeline);
    }
}
