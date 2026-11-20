package com.podcastapp.legacy;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Opens picker results at playback time; a remembered URI is not a read grant. */
final class LocalAudio {
    private LocalAudio() { }

    static boolean isPickerUri(Uri uri) {
        if (uri == null || !uri.isHierarchical()) return false;
        if ("content".equals(uri.getScheme())) {
            return uri.getAuthority() != null && uri.getAuthority().length() != 0;
        }
        return "file".equals(uri.getScheme())
                && (uri.getAuthority() == null || uri.getAuthority().length() == 0)
                && uri.getQuery() == null && uri.getFragment() == null
                && uri.getPath() != null && uri.getPath().startsWith("/");
    }

    static void setDataSource(Context context, MediaPlayer player, Uri uri) throws IOException {
        if (!isPickerUri(uri)) throw new IOException("Unsupported picker URI");
        if ("content".equals(uri.getScheme())) {
            AssetFileDescriptor asset = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (asset == null) throw new IOException("No file descriptor");
            try {
                if (!asset.getFileDescriptor().valid()) throw new IOException("Invalid file descriptor");
                long length = asset.getDeclaredLength();
                if (length >= 0) {
                    player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), length);
                } else if (asset.getStartOffset() == 0) {
                    player.setDataSource(asset.getFileDescriptor());
                } else {
                    throw new IOException("Unknown length at nonzero offset");
                }
            } finally {
                asset.close();
            }
            return;
        }

        // Raw picker paths may be used only under canonical shared storage, not app-private paths.
        File shared = Environment.getExternalStorageDirectory().getCanonicalFile();
        File file = new File(uri.getPath()).getCanonicalFile();
        String root = shared.getPath() + File.separator;
        String path = file.getPath();
        if (shared.getParent() == null || !path.startsWith(root)
                || path.startsWith(root + "Android" + File.separator)
                || !file.isFile() || !file.canRead()) {
            throw new IOException("File is not readable in shared storage");
        }
        FileInputStream input = new FileInputStream(file);
        try {
            player.setDataSource(input.getFD());
        } finally {
            input.close();
        }
    }
}
