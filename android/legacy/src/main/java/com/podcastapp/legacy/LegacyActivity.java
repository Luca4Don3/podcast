package com.podcastapp.legacy;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * System-view-only entry point for Android 4.0 and later. No AndroidX, no
 * resources requiring API >= 15, no background service and no native code.
 */
public final class LegacyActivity extends Activity
        implements LegacyPlayer.Listener, AdapterView.OnItemClickListener,
                   SeekBar.OnSeekBarChangeListener, View.OnClickListener {

    private static final int PICK_AUDIO = 71;
    private static final int PICK_OPML = 72;

    private final FeedRepository feeds = new FeedRepository();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LegacyPlayer player;
    private EditText urlInput;
    private TextView playStatus;
    private TextView feedStatus;
    private ListView episodes;
    private Button playButton;
    private Button localButton;
    private Button audioButton;
    private Button feedButton;
    private Button cancelButton;
    private Button importButton;
    private SeekBar seek;
    private EntryAdapter adapter;
    private FeedRepository.Cancellation pending;
    private Thread feedThread;
    private boolean dragging;
    private int durationMs;
    private int playPositionMs;
    private int feedRequest;

    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        player = new LegacyPlayer(this, this);
        setContentView(buildUi());
        adapter = new EntryAdapter(this);
        episodes.setAdapter(adapter);
        episodes.setOnItemClickListener(this);
        episodes.setEmptyView(feedStatus);
        playButton.setOnClickListener(this);
        findViewById(R.id.legacy_stop).setOnClickListener(this);
        findViewById(R.id.legacy_rewind).setOnClickListener(this);
        findViewById(R.id.legacy_forward).setOnClickListener(this);
        localButton.setOnClickListener(this);
        audioButton.setOnClickListener(this);
        feedButton.setOnClickListener(this);
        cancelButton.setOnClickListener(this);
        importButton.setOnClickListener(this);
        seek.setOnSeekBarChangeListener(this);
        feedStatus.setText(R.string.legacy_choose_source);
        updatePlayback(LegacyPlayer.State.FINISHED, LegacyPlayer.Failure.NONE, 0, 0, false);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xfff5f5f5);

        feedStatus = new TextView(this);
        feedStatus.setId(R.id.legacy_feed_status);
        feedStatus.setTextSize(15f);
        feedStatus.setMinHeight(dp(40));
        root.addView(feedStatus, row());

        LinearLayout sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.HORIZONTAL);
        localButton = button(R.id.legacy_local, R.string.legacy_local);
        audioButton = button(R.id.legacy_audio_url, R.string.legacy_audio_url);
        feedButton = button(R.id.legacy_feed, R.string.legacy_feed);
        importButton = button(R.id.legacy_import_opml, R.string.legacy_import_opml);
        sources.addView(localButton, weight());
        sources.addView(audioButton, weight());
        sources.addView(feedButton, weight());
        root.addView(sources, row());
        root.addView(importButton, row());
        localButton.setNextFocusRightId(R.id.legacy_audio_url);
        audioButton.setNextFocusLeftId(R.id.legacy_local);
        audioButton.setNextFocusRightId(R.id.legacy_feed);
        feedButton.setNextFocusLeftId(R.id.legacy_audio_url);
        importButton.setNextFocusUpId(R.id.legacy_feed);
        localButton.requestFocus();

        urlInput = new EditText(this);
        urlInput.setId(R.id.legacy_url_input);
        urlInput.setHint(R.string.legacy_url_hint);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(16f);
        root.addView(urlInput, row());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        cancelButton = button(R.id.legacy_cancel_feed, R.string.legacy_cancel_feed);
        actions.addView(cancelButton, weight());
        root.addView(actions, row());
        cancelButton.setVisibility(View.GONE);

        episodes = new ListView(this);
        episodes.setId(R.id.legacy_episodes);
        episodes.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        root.addView(episodes, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        playStatus = new TextView(this);
        playStatus.setId(R.id.legacy_play_status);
        playStatus.setTextSize(14f);
        playStatus.setMinHeight(dp(26));
        root.addView(playStatus, row());

        seek = new SeekBar(this);
        seek.setId(R.id.legacy_seek);
        seek.setMax(1000);
        root.addView(seek, row());

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.addView(button(R.id.legacy_rewind, R.string.legacy_rewind), weight());
        playButton = button(R.id.legacy_play, R.string.legacy_play);
        transport.addView(playButton, weight());
        transport.addView(button(R.id.legacy_forward, R.string.legacy_forward), weight());
        transport.addView(button(R.id.legacy_stop, R.string.legacy_stop), weight());
        root.addView(transport, row());
        return root;
    }

    private Button button(int id, int label) {
        Button b = new Button(this);
        b.setId(id);
        b.setText(label);
        b.setTextSize(14f);
        return b;
    }

    private LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.legacy_local) {
            pick(PICK_AUDIO, "audio/*");
        } else if (id == R.id.legacy_import_opml) {
            pick(PICK_OPML, "*/*");
        } else if (id == R.id.legacy_play) {
            player.toggle();
        } else if (id == R.id.legacy_stop) {
            player.stop();
            updatePlayback(LegacyPlayer.State.FINISHED, LegacyPlayer.Failure.NONE, 0, 0, false);
        } else if (id == R.id.legacy_rewind) {
            player.seek(playPositionMs - 30000);
        } else if (id == R.id.legacy_forward) {
            player.seek(playPositionMs + 30000);
        } else if (id == R.id.legacy_audio_url || id == R.id.legacy_feed) {
            String text = urlInput.getText().toString().trim();
            if (!FeedRepository.isHttpUrl(text)) {
                toast(R.string.legacy_bad_url);
                return;
            }
            if (id == R.id.legacy_audio_url) playAddress(text, 0);
            else openFeed(text);
        } else if (id == R.id.legacy_cancel_feed) {
            cancelFeed();
        }
    }

    private void pick(int request, String type) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType(type);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, getString(R.string.legacy_open)), request);
        } catch (android.content.ActivityNotFoundException e) {
            toast(R.string.legacy_no_picker);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_AUDIO) {
            player.load(uri, null, 0);
            playStatus.setText(R.string.legacy_preparing);
        } else if (request == PICK_OPML) {
            importOpml(uri);
        }
    }

    private void importOpml(Uri uri) {
        cancelFeed();
        final FeedRepository.Cancellation token = new FeedRepository.Cancellation();
        final int request = ++feedRequest;
        pending = token;
        showFeedBusy(true, R.string.legacy_loading_feed);
        ContentResolver resolver = getContentResolver();
        InputStream stream = null;
        try {
            stream = resolver.openInputStream(uri);
            final List<FeedRepository.Subscription> subscriptions = feeds.importOpml(stream, token);
            main.post(new Runnable() {
                @Override public void run() {
                    if (request != feedRequest || token.isCancelled()) return;
                    showFeedBusy(false, 0);
                    adapter.setItems(subscriptions, null);
                    if (subscriptions.isEmpty()) toast(R.string.legacy_feed_empty);
                }
            });
        } catch (IOException e) {
            final boolean cancelled = token.isCancelled();
            main.post(new Runnable() {
                @Override public void run() {
                    if (request != feedRequest) return;
                    showFeedBusy(false, 0);
                    toast(cancelled ? R.string.legacy_feed_cancelled : R.string.legacy_feed_error);
                }
            });
        } finally {
            if (stream != null) try { stream.close(); } catch (IOException ignored) { }
        }
    }

    private void openFeed(final String address) {
        cancelFeed();
        final FeedRepository.Cancellation token = new FeedRepository.Cancellation();
        final int request = ++feedRequest;
        pending = token;
        showFeedBusy(true, R.string.legacy_loading_feed);
        feedThread = new Thread(new Runnable() {
            @Override public void run() {
                FeedRepository.Feed feed = null;
                IOException failure = null;
                try {
                    feed = feeds.openFeed(address, token);
                } catch (IOException e) {
                    failure = e;
                }
                final FeedRepository.Feed result = feed;
                final boolean cancelled = token.isCancelled()
                        || failure instanceof InterruptedIOException;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (request != feedRequest) return;
                        showFeedBusy(false, 0);
                        if (result == null) {
                            toast(cancelled ? R.string.legacy_feed_cancelled : R.string.legacy_feed_error);
                            return;
                        }
                        urlInput.setText(result.url);
                        adapter.setItems(null, result.episodes);
                        feedStatus.setText(result.title);
                        if (result.episodes.isEmpty()) toast(R.string.legacy_feed_empty);
                    }
                });
            }
        }, "legacy-feed");
        feedThread.setDaemon(true);
        feedThread.start();
    }

    private void cancelFeed() {
        feedRequest++;
        if (pending != null) pending.cancel();
        pending = null;
        showFeedBusy(false, 0);
    }

    private void showFeedBusy(boolean busy, int label) {
        cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        feedButton.setEnabled(!busy);
        importButton.setEnabled(!busy);
        if (label != 0) feedStatus.setText(label);
    }

    @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Object item = adapter.getItem(position);
        if (item instanceof FeedRepository.Episode) {
            FeedRepository.Episode episode = (FeedRepository.Episode) item;
            if (FeedRepository.isHttpUrl(episode.mediaUrl)) playAddress(episode.mediaUrl, 0);
            else toast(R.string.legacy_bad_url);
        } else if (item instanceof FeedRepository.Subscription) {
            String url = ((FeedRepository.Subscription) item).url;
            if (FeedRepository.isHttpUrl(url)) openFeed(url);
            else toast(R.string.legacy_bad_url);
        }
    }

    private void playAddress(String address, int resumeAt) {
        player.load(null, address, resumeAt);
        playStatus.setText(R.string.legacy_preparing);
    }

    @Override public void onPlayback(LegacyPlayer.State state, LegacyPlayer.Failure failure,
            int position, int length, boolean seekable) {
        if (!isFinishing()) updatePlayback(state, failure, position, length, seekable);
    }

    private void updatePlayback(LegacyPlayer.State state, LegacyPlayer.Failure failure,
            int position, int length, boolean seekable) {
        int label = R.string.legacy_no_source;
        if (state == LegacyPlayer.State.PREPARING) label = R.string.legacy_preparing;
        else if (state == LegacyPlayer.State.PLAYING) label = R.string.legacy_playing;
        else if (state == LegacyPlayer.State.PAUSED) label = R.string.legacy_paused;
        else if (state == LegacyPlayer.State.FINISHED) label = R.string.legacy_finished;
        else if (failure == LegacyPlayer.Failure.LOCAL_FILE) label = R.string.legacy_local_error;
        else if (failure == LegacyPlayer.Failure.TIMEOUT) label = R.string.legacy_timeout;
        else if (failure == LegacyPlayer.Failure.AUDIO_FOCUS) label = R.string.legacy_focus_error;
        else if (failure == LegacyPlayer.Failure.MEDIA) label = R.string.legacy_media_error;
        playStatus.setText(label);
        playButton.setText(state == LegacyPlayer.State.PLAYING
                ? R.string.legacy_pause : R.string.legacy_play);
        playPositionMs = Math.max(0, position);
        durationMs = Math.max(0, length);
        seek.setEnabled(seekable && durationMs > 0);
        if (!dragging && durationMs > 0) {
            seek.setProgress(Math.min(1000, (int) (1000L * playPositionMs / durationMs)));
        }
    }

    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (!fromUser || durationMs <= 0) return;
        int target = (int) ((long) durationMs * progress / 1000L);
        playStatus.setText(getString(R.string.legacy_time_pair,
                time(target), time(durationMs)));
    }

    @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }

    @Override public void onStopTrackingTouch(SeekBar bar) {
        dragging = false;
        if (durationMs > 0) {
            int target = (int) ((long) durationMs * bar.getProgress() / 1000L);
            player.seek(target);
        }
    }

    private static String time(int ms) {
        int seconds = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void toast(int res) {
        if (!isFinishing()) Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        cancelFeed();
        player.close();
        super.onDestroy();
    }

    private static final class EntryAdapter extends ArrayAdapter<Object> {
        EntryAdapter(Activity activity) {
            super(activity, android.R.layout.simple_list_item_1, new ArrayList<Object>());
        }

        void setItems(List<FeedRepository.Subscription> subscriptions,
                      List<FeedRepository.Episode> episodeList) {
            clear();
            if (subscriptions != null) addAll(subscriptions);
            if (episodeList != null) addAll(episodeList);
            notifyDataSetChanged();
        }

        @Override public View getView(int position, View reuse, ViewGroup parent) {
            View view = super.getView(position, reuse, parent);
            TextView text = (TextView) view.findViewById(android.R.id.text1);
            Object item = getItem(position);
            if (item instanceof FeedRepository.Episode) {
                String title = ((FeedRepository.Episode) item).title;
                text.setText(title == null || title.length() == 0
                        ? getContext().getString(R.string.legacy_empty_episode) : title);
            } else if (item instanceof FeedRepository.Subscription) {
                String title = ((FeedRepository.Subscription) item).title;
                text.setText(title == null || title.length() == 0
                        ? getContext().getString(R.string.legacy_unknown_feed) : title);
            }
            text.setTextSize(16f);
            return view;
        }
    }
}
