package com.podcastapp.legacy;

import java.io.IOException;
import java.io.InputStream;

/** Counts up to limit + 1 so a stream exactly at the limit is accepted if it ends there. */
final class FeedInput extends InputStream {
    private final InputStream source;
    private final FeedRepository.Cancellation cancellation;
    private final long deadline;
    private int remaining;

    FeedInput(InputStream source, int limit, FeedRepository.Cancellation cancellation, long deadline) {
        this.source = source;
        this.remaining = limit;
        this.cancellation = cancellation;
        this.deadline = deadline;
    }

    @Override public int read() throws IOException {
        FeedHttp.check(cancellation, deadline);
        int value = source.read();
        counted(value == -1 ? 0 : 1);
        return value;
    }

    @Override public int read(byte[] buffer, int offset, int count) throws IOException {
        FeedHttp.check(cancellation, deadline);
        int read = source.read(buffer, offset, Math.min(count, remaining + 1));
        counted(read);
        return read;
    }

    private void counted(int count) throws IOException {
        FeedHttp.check(cancellation, deadline);
        if (count > remaining) {
            throw new IOException("XML 内容太大");
        }
        if (count > 0) {
            remaining -= count;
        }
    }

    @Override public int available() throws IOException {
        return Math.min(source.available(), remaining + 1);
    }

    @Override public void close() throws IOException {
        source.close();
    }
}
