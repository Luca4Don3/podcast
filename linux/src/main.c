#include "feed.h"
#include "player.h"

#include <curl/curl.h>
#include <gtk/gtk.h>
#include <string.h>

typedef struct {
    GtkWidget *window, *url, *status, *list, *time, *seek, *play, *stop;
    GtkWidget *volume, *open_xml, *import, *cancel;
    GstElement *pipeline;
    guint bus_watch;
    gboolean playing, updating, closed, smoke_ok;
    GPtrArray *items;
    GCancellable *import_cancel;
} App;

typedef struct { char *location; gboolean local; } Import;
int smoke_test(void);

static void status(App *app, const char *message)
{
    gtk_label_set_text(GTK_LABEL(app->status), message);
}

static void clear_player(App *app)
{
    if (app->bus_watch) {
        g_source_remove(app->bus_watch);
        app->bus_watch = 0;
    }
    player_free(g_steal_pointer(&app->pipeline));
    app->playing = FALSE;
    if (!app->closed) {
        gtk_button_set_label(GTK_BUTTON(app->play), "Resume");
        gtk_widget_set_sensitive(app->play, FALSE);
        gtk_widget_set_sensitive(app->stop, FALSE);
    }
}

static gboolean bus_message(GstBus *bus, GstMessage *message, gpointer data)
{
    (void)bus;
    App *app = data;
    if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR || GST_MESSAGE_TYPE(message) == GST_MESSAGE_EOS) {
        gboolean error = GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR;
        gboolean redirected = FALSE;
        if (error) {
            const GstStructure *details = NULL;
            guint http_status = 0;
            gst_message_parse_error_details(message, &details);
            redirected = details && gst_structure_get_uint(details, "http-status-code", &http_status)
                && http_status >= 300 && http_status < 400;
        }
        /* Don't display plugin diagnostics: they can include a full URL or path. */
        status(app, redirected ? "Media redirects are blocked. Use a direct audio URL."
            : error ? "Playback failed. Check the URL/file, audio format, and output device."
            : "Playback finished.");
        app->bus_watch = 0;
        clear_player(app);
        return G_SOURCE_REMOVE;
    }
    if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_STATE_CHANGED && GST_MESSAGE_SRC(message) == GST_OBJECT(app->pipeline)) {
        GstState state;
        gst_message_parse_state_changed(message, NULL, &state, NULL);
        if (state == GST_STATE_PLAYING)
            status(app, "Playing.");
        else if (state == GST_STATE_PAUSED && !app->playing)
            status(app, "Paused.");
    }
    return G_SOURCE_CONTINUE;
}

static void change_volume(GtkScaleButton *button, double value, gpointer data)
{
    (void)button;
    App *app = data;
    if (app->pipeline) {
        g_autoptr(GstElement) volume = gst_bin_get_by_name(GST_BIN(app->pipeline), "playback-volume");
        g_object_set(volume, "volume", value, NULL);
    }
}

static void start_player(App *app, const char *source, gboolean remote)
{
    g_autofree char *url = remote ? http_url(source) : NULL;
    if (remote && !url) {
        status(app, "Enter a credential-free HTTP(S) audio URL.");
        return;
    }
    g_autoptr(GError) error = NULL;
    GstElement *pipeline = player_new(remote ? url : source, remote, FALSE, &error);
    if (!pipeline) {
        status(app, error->message);
        return;
    }
    clear_player(app);
    app->pipeline = pipeline;
    g_autoptr(GstBus) bus = gst_element_get_bus(pipeline);
    app->bus_watch = gst_bus_add_watch(bus, bus_message, app);
    change_volume(NULL, gtk_scale_button_get_value(GTK_SCALE_BUTTON(app->volume)), app);
    if (gst_element_set_state(pipeline, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) {
        clear_player(app);
        status(app, "Could not start playback.");
        return;
    }
    app->playing = TRUE;
    gtk_button_set_label(GTK_BUTTON(app->play), "Pause");
    gtk_widget_set_sensitive(app->play, TRUE);
    gtk_widget_set_sensitive(app->stop, TRUE);
    status(app, "Opening audio…");
}

static void play_url(GtkButton *button, gpointer data)
{
    (void)button;
    App *app = data;
    start_player(app, gtk_entry_get_text(GTK_ENTRY(app->url)), TRUE);
}

static void toggle(GtkButton *button, gpointer data)
{
    App *app = data;
    if (!app->pipeline)
        return;
    gboolean playing = !app->playing;
    if (gst_element_set_state(app->pipeline, playing ? GST_STATE_PLAYING : GST_STATE_PAUSED) == GST_STATE_CHANGE_FAILURE) {
        clear_player(app);
        status(app, "Could not change playback state.");
        return;
    }
    app->playing = playing;
    gtk_button_set_label(button, playing ? "Pause" : "Resume");
}

static void stop(GtkButton *button, gpointer data)
{
    (void)button;
    App *app = data;
    clear_player(app);
    status(app, "Stopped.");
}

static gboolean time_tick(gpointer data)
{
    App *app = data;
    if (app->closed)
        return G_SOURCE_CONTINUE;
    gint64 pos = 0, duration = 0;
    gboolean timed = app->pipeline && gst_element_query_position(app->pipeline, GST_FORMAT_TIME, &pos) && pos >= 0;
    gboolean finite = timed && gst_element_query_duration(app->pipeline, GST_FORMAT_TIME, &duration) && duration > 0;
    gboolean seekable = FALSE;
    if (finite) {
        g_autoptr(GstQuery) query = gst_query_new_seeking(GST_FORMAT_TIME);
        if (gst_element_query(app->pipeline, query))
            gst_query_parse_seeking(query, NULL, &seekable, NULL, NULL);
    }
    app->updating = TRUE;
    gtk_widget_set_sensitive(app->seek, seekable);
    gtk_range_set_value(GTK_RANGE(app->seek), finite ? 100.0 * pos / duration : 0);
    app->updating = FALSE;
    gint64 p = timed ? pos / GST_SECOND : 0, d = finite ? duration / GST_SECOND : 0;
    char label[100];
    g_snprintf(label, sizeof label, "%" G_GINT64_FORMAT ":%02" G_GINT64_FORMAT " / %" G_GINT64_FORMAT ":%02" G_GINT64_FORMAT,
               p / 60, p % 60, d / 60, d % 60);
    gtk_label_set_text(GTK_LABEL(app->time), label);
    return G_SOURCE_CONTINUE;
}

static void seek(GtkRange *range, gpointer data)
{
    App *app = data;
    gint64 duration = 0;
    if (!app->updating && app->pipeline && gst_element_query_duration(app->pipeline, GST_FORMAT_TIME, &duration) && duration > 0) {
        gdouble ratio = gtk_range_get_value(range) / 100.0;
        gint64 target = ratio >= 1.0 ? duration : (gint64)(ratio * duration);
        if (!gst_element_seek_simple(app->pipeline, GST_FORMAT_TIME, GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT, target))
            status(app, "This audio cannot seek to the requested position.");
    }
}

static void import_free(gpointer ptr)
{
    Import *request = ptr;
    g_free(request->location);
    g_free(request);
}

static void import_worker(GTask *task, gpointer source, gpointer data, GCancellable *cancel)
{
    (void)source;
    Import *request = data;
    GError *error = NULL;
    GPtrArray *items = feed_import(request->location, request->local, cancel, &error);
    if (items)
        g_task_return_pointer(task, items, (GDestroyNotify)g_ptr_array_unref);
    else
        g_task_return_error(task, error);
}

static void import_done(GObject *source, GAsyncResult *result, gpointer data)
{
    (void)source;
    App *app = data;
    g_autoptr(GError) error = NULL;
    g_autoptr(GPtrArray) items = g_task_propagate_pointer(G_TASK(result), &error);
    g_clear_object(&app->import_cancel);
    if (app->closed) {
        gtk_main_quit();
        return;
    }
    gtk_widget_set_sensitive(app->open_xml, TRUE);
    gtk_widget_set_sensitive(app->import, TRUE);
    gtk_widget_set_sensitive(app->cancel, FALSE);
    if (!items) {
        status(app, error->message);
        return;
    }
    GList *rows = gtk_container_get_children(GTK_CONTAINER(app->list));
    for (GList *row = rows; row; row = row->next)
        gtk_widget_destroy(row->data);
    g_list_free(rows);
    g_clear_pointer(&app->items, g_ptr_array_unref);
    app->items = g_steal_pointer(&items);
    for (guint i = 0; i < app->items->len; ++i) {
        FeedItem *item = g_ptr_array_index(app->items, i);
        GtkWidget *row = gtk_list_box_row_new();
        GtkWidget *label = gtk_label_new(item->title);
        gtk_label_set_xalign(GTK_LABEL(label), 0.0);
        gtk_label_set_single_line_mode(GTK_LABEL(label), TRUE);
        gtk_label_set_ellipsize(GTK_LABEL(label), PANGO_ELLIPSIZE_END);
        gtk_widget_set_margin_start(label, 10);
        gtk_widget_set_margin_end(label, 10);
        gtk_widget_set_margin_top(label, 5);
        gtk_widget_set_margin_bottom(label, 5);
        g_autoptr(GUri) uri = g_uri_parse(item->url, G_URI_FLAGS_ENCODED, NULL);
        g_autofree char *tip = g_strdup_printf("%s — %s", item->kind == FEED_AUDIO ? "Play" : "Import",
                                               uri ? g_uri_get_host(uri) : "HTTP(S)");
        gtk_widget_set_tooltip_text(row, tip);
        g_object_set_data(G_OBJECT(row), "item-index", GUINT_TO_POINTER(i + 1));
        gtk_container_add(GTK_CONTAINER(row), label);
        gtk_container_add(GTK_CONTAINER(app->list), row);
    }
    gtk_widget_show_all(app->list);
    g_autofree char *summary = g_strdup_printf("%u entries. Double-click or press Enter to open.", app->items->len);
    status(app, app->items->len > 0 ? summary : "No playable episodes or subscriptions found.");
}

static void start_import(App *app, const char *location, gboolean local)
{
    if (app->import_cancel)
        return;
    Import *request = g_new0(Import, 1);
    request->location = g_strdup(location);
    request->local = local;
    app->import_cancel = g_cancellable_new();
    g_autoptr(GTask) task = g_task_new(NULL, app->import_cancel, import_done, app);
    g_task_set_task_data(task, request, import_free);
    gtk_widget_set_sensitive(app->open_xml, FALSE);
    gtk_widget_set_sensitive(app->import, FALSE);
    gtk_widget_set_sensitive(app->cancel, TRUE);
    status(app, "Importing XML…");
    g_task_run_in_thread(task, import_worker);
}

static void import_url(GtkButton *button, gpointer data)
{
    (void)button;
    App *app = data;
    start_import(app, gtk_entry_get_text(GTK_ENTRY(app->url)), FALSE);
}

static void cancel_import(GtkButton *button, gpointer data)
{
    (void)button;
    App *app = data;
    if (app->import_cancel) {
        g_cancellable_cancel(app->import_cancel);
        gtk_widget_set_sensitive(app->cancel, FALSE);
    }
}

static void file_response(GtkDialog *dialog, gint response, gpointer data)
{
    App *app = data;
    gboolean xml = GPOINTER_TO_INT(g_object_get_data(G_OBJECT(dialog), "import-xml"));
    g_autofree char *name = response == GTK_RESPONSE_ACCEPT
        ? gtk_file_chooser_get_filename(GTK_FILE_CHOOSER(dialog)) : NULL;
    gtk_widget_destroy(GTK_WIDGET(dialog));
    if (name && !app->closed) {
        if (xml)
            start_import(app, name, TRUE);
        else
            start_player(app, name, FALSE);
    }
}

static void open_file(App *app, gboolean xml)
{
    GtkWidget *dialog = gtk_file_chooser_dialog_new(xml ? "Import RSS, Atom or OPML" : "Open audio file",
        GTK_WINDOW(app->window), GTK_FILE_CHOOSER_ACTION_OPEN, "_Cancel", GTK_RESPONSE_CANCEL,
        "_Open", GTK_RESPONSE_ACCEPT, NULL);
    gtk_window_set_modal(GTK_WINDOW(dialog), TRUE);
    gtk_window_set_destroy_with_parent(GTK_WINDOW(dialog), TRUE);
    gtk_file_chooser_set_local_only(GTK_FILE_CHOOSER(dialog), TRUE);
    g_object_set_data(G_OBJECT(dialog), "import-xml", GINT_TO_POINTER(xml));
    g_signal_connect(dialog, "response", G_CALLBACK(file_response), app);
    gtk_widget_show(dialog);
}

static void open_audio(GtkButton *button, gpointer data) { (void)button; open_file(data, FALSE); }
static void open_xml(GtkButton *button, gpointer data) { (void)button; open_file(data, TRUE); }

static void activate_row(GtkListBox *box, GtkListBoxRow *row, gpointer data)
{
    (void)box;
    App *app = data;
    guint i = GPOINTER_TO_UINT(g_object_get_data(G_OBJECT(row), "item-index"));
    if (i == 0 || !app->items || i > app->items->len)
        return;
    FeedItem *item = g_ptr_array_index(app->items, i - 1);
    if (item->kind == FEED_AUDIO)
        start_player(app, item->url, TRUE);
    else
        start_import(app, item->url, FALSE);
}

static void closed(GtkWidget *widget, gpointer data)
{
    (void)widget;
    App *app = data;
    app->closed = TRUE;
    clear_player(app);
    if (app->import_cancel)
        g_cancellable_cancel(app->import_cancel);
    else
        gtk_main_quit();
}

static GtkWidget *button(GtkWidget *box, const char *label, GCallback fn, App *app)
{
    GtkWidget *widget = gtk_button_new_with_label(label);
    gtk_box_pack_start(GTK_BOX(box), widget, FALSE, FALSE, 0);
    g_signal_connect(widget, "clicked", fn, app);
    return widget;
}

static void make_window(App *app)
{
    app->window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(app->window), "Podcast Linux");
    gtk_window_set_default_size(GTK_WINDOW(app->window), 730, 440);
    gtk_container_set_border_width(GTK_CONTAINER(app->window), 12);
    g_signal_connect(app->window, "destroy", G_CALLBACK(closed), app);
    GtkWidget *body = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    gtk_container_add(GTK_CONTAINER(app->window), body);
    app->url = gtk_entry_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(app->url), "HTTP(S) audio or feed URL");
    gtk_entry_set_max_length(GTK_ENTRY(app->url), FEED_MAX_URL);
    g_signal_connect(app->url, "activate", G_CALLBACK(play_url), app);
    gtk_box_pack_start(GTK_BOX(body), app->url, FALSE, FALSE, 0);
    GtkWidget *sources = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    gtk_box_pack_start(GTK_BOX(body), sources, FALSE, FALSE, 0);
    button(sources, "Play URL", G_CALLBACK(play_url), app);
    button(sources, "Open audio…", G_CALLBACK(open_audio), app);
    app->import = button(sources, "Import feed URL", G_CALLBACK(import_url), app);
    app->open_xml = button(sources, "Open XML…", G_CALLBACK(open_xml), app);
    app->cancel = button(sources, "Cancel import", G_CALLBACK(cancel_import), app);
    gtk_widget_set_sensitive(app->cancel, FALSE);
    GtkWidget *scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_box_pack_start(GTK_BOX(body), scroll, TRUE, TRUE, 0);
    app->list = gtk_list_box_new();
    gtk_list_box_set_activate_on_single_click(GTK_LIST_BOX(app->list), FALSE);
    gtk_container_add(GTK_CONTAINER(scroll), app->list);
    g_signal_connect(app->list, "row-activated", G_CALLBACK(activate_row), app);
    GtkWidget *transport = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    gtk_box_pack_start(GTK_BOX(body), transport, FALSE, FALSE, 0);
    app->play = button(transport, "Resume", G_CALLBACK(toggle), app);
    app->stop = button(transport, "Stop", G_CALLBACK(stop), app);
    gtk_widget_set_sensitive(app->play, FALSE);
    gtk_widget_set_sensitive(app->stop, FALSE);
    app->seek = gtk_scale_new_with_range(GTK_ORIENTATION_HORIZONTAL, 0, 100, 0.1);
    gtk_scale_set_draw_value(GTK_SCALE(app->seek), FALSE);
    gtk_widget_set_sensitive(app->seek, FALSE);
    gtk_box_pack_start(GTK_BOX(transport), app->seek, TRUE, TRUE, 0);
    g_signal_connect(app->seek, "value-changed", G_CALLBACK(seek), app);
    app->time = gtk_label_new("0:00 / 0:00");
    gtk_box_pack_start(GTK_BOX(transport), app->time, FALSE, FALSE, 0);
    app->volume = gtk_volume_button_new();
    gtk_scale_button_set_value(GTK_SCALE_BUTTON(app->volume), 0.8);
    gtk_box_pack_start(GTK_BOX(transport), app->volume, FALSE, FALSE, 0);
    g_signal_connect(app->volume, "value-changed", G_CALLBACK(change_volume), app);
    app->status = gtk_label_new("Open a local audio file or enter an HTTP(S) URL.");
    gtk_label_set_xalign(GTK_LABEL(app->status), 0);
    gtk_label_set_line_wrap(GTK_LABEL(app->status), TRUE);
    gtk_box_pack_start(GTK_BOX(body), app->status, FALSE, FALSE, 0);
    gtk_widget_show_all(app->window);
}

static gboolean window_smoke(gpointer data)
{
    App *app = data;
    app->smoke_ok = gtk_widget_get_mapped(app->window) && gtk_widget_get_window(app->window)
        && gtk_widget_get_mapped(app->url) && !gtk_widget_get_sensitive(app->play);
    gtk_widget_destroy(app->window);
    return G_SOURCE_REMOVE;
}

int main(int argc, char **argv)
{
    gboolean core = argc == 2 && !strcmp(argv[1], "--smoke-test");
    gboolean window = argc == 2 && !strcmp(argv[1], "--window-smoke-test");
    gboolean help = argc == 2 && !strcmp(argv[1], "--help");
    if (help || (argc != 1 && !core && !window)) {
        g_print("Usage: podcast-linux [--smoke-test | --window-smoke-test | --help]\n");
        return help ? 0 : 2;
    }
    g_autoptr(GError) error = NULL;
    if (!gst_init_check(NULL, NULL, &error)) {
        g_printerr("GStreamer initialization failed: %s\n", error->message);
        return 1;
    }
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
        g_printerr("HTTP initialization failed.\n");
        return 1;
    }
    int result = 0;
    if (core) {
        result = smoke_test();
    } else if (!gtk_init_check(NULL, NULL)) {
        g_printerr("Could not open a GTK display. Use --smoke-test for headless checks.\n");
        result = 1;
    } else {
        App app = {0};
        make_window(&app);
        guint clock = 0;
        if (window)
            g_idle_add(window_smoke, &app);
        else
            clock = g_timeout_add(250, time_tick, &app);
        gtk_main();
        if (clock)
            g_source_remove(clock);
        g_clear_pointer(&app.items, g_ptr_array_unref);
        if (window) {
            g_print("Window smoke: %s\n", app.smoke_ok ? "PASS" : "FAIL");
            result = app.smoke_ok ? 0 : 1;
        }
    }
    curl_global_cleanup();
    return result;
}
