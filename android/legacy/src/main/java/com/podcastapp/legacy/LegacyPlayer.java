package com.podcastapp.legacy;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.IOException;

/** Foreground-only player. One worker owns the native player and all of its callbacks. */
final class LegacyPlayer {
    enum State { PREPARING, PLAYING, PAUSED, FINISHED, ERROR }
    enum Failure { NONE, LOCAL_FILE, MEDIA, TIMEOUT, AUDIO_FOCUS }

    interface Listener {
        void onPlayback(State state, Failure failure, int position, int duration, boolean seekable);
    }

    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private HandlerThread thread;
    private Handler worker;
    private volatile Request active;

    // Worker thread only.
    private MediaPlayer player;
    private State state;
    private int duration;
    private int position;
    private boolean seekable;
    private int seekTarget = -1;
    private int queuedSeek = -1;
    private boolean startAfterSeek;
    private boolean focused;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    private static final class Request {
        final Uri local;
        final String address;
        final int resumeAt;

        Request(Uri local, String address, int resumeAt) {
            this.local = local;
            this.address = address;
            this.resumeAt = resumeAt;
        }
    }

    private final Runnable deadline = new Runnable() {
        @Override public void run() {
            if (active == null) return;
            stop();
            if (listener != null) listener.onPlayback(State.ERROR, Failure.TIMEOUT, 0, 0, false);
        }
    };

    private final Runnable progress = new Runnable() {
        @Override public void run() {
            Request request = active;
            if (request == null || player == null || state != State.PLAYING) return;
            try {
                send(request, Failure.NONE);
                worker.postDelayed(this, 1000);
            } catch (RuntimeException e) {
                fail(request, Failure.MEDIA);
            }
        }
    };

    private final Runnable seekDeadline = new Runnable() {
        @Override public void run() {
            if (active == null || player == null || seekTarget < 0) return;
            try {
                stopSeeking(active);
            } catch (RuntimeException e) {
                fail(active, Failure.MEDIA);
            }
        }
    };

    LegacyPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    void load(Uri local, String address, int resumeAt) {
        if (listener == null) return;
        if (thread == null) {
            thread = new HandlerThread("legacy-media");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        final Request request = new Request(local, address, resumeAt);
        active = request;
        main.removeCallbacks(deadline);
        main.postDelayed(deadline, 30 * 1000);
        worker.post(new Runnable() {
            @Override public void run() {
                if (active == request) open(request);
            }
        });
    }

    void toggle() {
        final Request request = active;
        if (request == null) return;
        worker.post(new Runnable() {
            @Override public void run() {
                if (active != request || player == null) return;
                try {
                    if (state == State.PLAYING) pause(request);
                    else if (state == State.PAUSED) start(request);
                } catch (RuntimeException e) {
                    fail(request, Failure.MEDIA);
                }
            }
        });
    }

    void seek(final int target) {
        final Request request = active;
        if (request == null) return;
        worker.post(new Runnable() {
            @Override public void run() {
                if (active != request || player == null || !seekable) return;
                try {
                    int clamped = Math.max(0, Math.min(target, duration - 1));
                    if (seekTarget >= 0) queuedSeek = clamped;
                    else beginSeek(clamped);
                    send(request, Failure.NONE);
                } catch (RuntimeException e) {
                    fail(request, Failure.MEDIA);
                }
            }
        });
    }

    void stop() {
        active = null;
        main.removeCallbacks(deadline);
        if (worker != null) {
            worker.post(new Runnable() {
                @Override public void run() { release(); }
            });
        }
    }

    void close() {
        active = null;
        listener = null;
        main.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            worker.post(new Runnable() {
                @Override public void run() {
                    release();
                    thread.quit(); // quitSafely requires API 18.
                }
            });
        }
    }

    private void open(final Request request) {
        release();
        state = State.PREPARING;
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    if (!isCurrent(mp, request)) return;
                    try {
                        duration = Math.max(0, mp.getDuration());
                        seekable = seekable && duration > 0;
                        state = State.PAUSED;
                        if (seekable && request.resumeAt > 0) {
                            startAfterSeek = true;
                            beginSeek(Math.min(request.resumeAt, duration - 1));
                            // Stay in preparing UI until the seek has finished and start() runs.
                        } else {
                            start(request);
                        }
                    } catch (RuntimeException e) {
                        fail(request, Failure.MEDIA);
                    }
                }
            });
            player.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                @Override public void onSeekComplete(MediaPlayer mp) {
                    if (!isCurrent(mp, request) || seekTarget < 0) return;
                    worker.removeCallbacks(seekDeadline);
                    seekTarget = -1;
                    try {
                        if (queuedSeek >= 0) {
                            int next = queuedSeek;
                            queuedSeek = -1;
                            beginSeek(next);
                        }
                        if (startAfterSeek) {
                            startAfterSeek = false;
                            start(request);
                        } else {
                            send(request, Failure.NONE);
                        }
                    } catch (RuntimeException e) {
                        fail(request, Failure.MEDIA);
                    }
                }
            });
            player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (isCurrent(mp, request) && what == MediaPlayer.MEDIA_INFO_NOT_SEEKABLE) {
                        try {
                            stopSeeking(request);
                        } catch (RuntimeException e) {
                            fail(request, Failure.MEDIA);
                        }
                        return true;
                    }
                    return false;
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (isCurrent(mp, request)) fail(request, Failure.MEDIA);
                    return true;
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    if (!isCurrent(mp, request)) return;
                    release();
                    deliver(request, State.FINISHED, Failure.NONE, 0, 0, false);
                }
            });
            focusListener = new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(final int change) {
                    if (change >= 0) return;
                    worker.post(new Runnable() {
                        @Override public void run() {
                            if (active != request || player == null || !focused
                                    || state != State.PLAYING) return;
                            try {
                                pause(request);
                            } catch (RuntimeException e) {
                                fail(request, Failure.MEDIA);
                            }
                        }
                    });
                }
            };
            seekable = true;
            if (request.local != null) LocalAudio.setDataSource(context, player, request.local);
            else player.setDataSource(request.address);
            if (active == request) player.prepareAsync();
            else release();
        } catch (IOException e) {
            fail(request, request.local == null ? Failure.MEDIA : Failure.LOCAL_FILE);
        } catch (SecurityException e) {
            fail(request, request.local == null ? Failure.MEDIA : Failure.LOCAL_FILE);
        } catch (RuntimeException e) {
            fail(request, Failure.MEDIA);
        }
    }

    private boolean isCurrent(MediaPlayer mp, Request request) {
        return active == request && player == mp;
    }

    private void beginSeek(int target) {
        seekTarget = target;
        player.seekTo(target);
        worker.postDelayed(seekDeadline, 8000);
    }

    private void stopSeeking(Request request) {
        seekable = false;
        seekTarget = queuedSeek = -1;
        worker.removeCallbacks(seekDeadline);
        if (startAfterSeek) {
            startAfterSeek = false;
            start(request);
        } else if (state != State.PREPARING) {
            send(request, Failure.NONE);
        }
    }

    private void start(Request request) {
        if (active != request) return;
        if (!focused) {
            focused = audio != null && audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        if (!focused) {
            send(request, Failure.AUDIO_FOCUS);
            return;
        }
        player.start();
        state = State.PLAYING;
        worker.removeCallbacks(progress);
        worker.postDelayed(progress, 1000);
        send(request, Failure.NONE);
    }

    private void pause(Request request) {
        player.pause();
        state = State.PAUSED;
        worker.removeCallbacks(progress);
        abandonFocus();
        send(request, Failure.NONE);
    }

    private void send(Request request, Failure failure) {
        int pending = queuedSeek >= 0 ? queuedSeek : seekTarget;
        // Use the actual position for persistence, even while a seek is pending.
        if (pending < 0) position = Math.max(0, player.getCurrentPosition());
        deliver(request, state, failure, position, duration, seekable);
    }

    private void deliver(final Request request, final State snapshot, final Failure failure,
            final int pos, final int length, final boolean canSeek) {
        main.post(new Runnable() {
            @Override public void run() {
                if (listener == null || active != request) return;
                main.removeCallbacks(deadline);
                if (snapshot == State.ERROR || snapshot == State.FINISHED) active = null;
                listener.onPlayback(snapshot, failure, pos, length, canSeek);
            }
        });
    }

    private void fail(Request request, Failure failure) {
        release();
        deliver(request, State.ERROR, failure, 0, 0, false);
    }

    private void abandonFocus() {
        if (focused) {
            focused = false;
            if (audio != null) audio.abandonAudioFocus(focusListener);
        }
    }

    private void release() {
        worker.removeCallbacks(progress);
        worker.removeCallbacks(seekDeadline);
        if (player != null) {
            try {
                player.release();
            } finally {
                player = null;
            }
        }
        abandonFocus();
        focusListener = null;
        duration = position = 0;
        seekable = startAfterSeek = false;
        seekTarget = queuedSeek = -1;
    }
}
