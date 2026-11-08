package com.termux.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.terminal.TerminalSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * e16tae fork: attach files to the terminal input.
 *
 * Picks files with the system picker (or takes them from the clipboard or the keyboard), copies
 * them to ~/downloads/attach and pastes their paths into the current session. If ~/bin/termux-attach-hook exists, it is run with each copied file
 * and the last line it prints is pasted instead, so it can e.g. upload the file to the machine
 * the terminal is connected to and print the remote path.
 */
public final class FileAttacher {

    public static final int REQUEST_CODE = 4711;

    private static final String LOG_TAG = "FileAttacher";
    private static final String ATTACH_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/downloads/attach";
    private static final String HOOK_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-attach-hook";
    private static final long HOOK_TIMEOUT_SECONDS = 180;

    private final TermuxActivity mActivity;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mBusy;

    public FileAttacher(TermuxActivity activity) {
        mActivity = activity;
    }

    public void pick() {
        if (mBusy) {
            toast(R.string.e16tae_attach_busy);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            mActivity.startActivityForResult(intent, REQUEST_CODE);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Cannot open the file picker", e);
            toast(R.string.e16tae_attach_no_picker);
        }
    }

    /** @return whether the result was for this class. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;

        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        attach(uris, null);
        return true;
    }

    /**
     * Attaches the files on the clipboard, e.g. a copied screenshot, if it holds files instead of
     * text.
     *
     * @return whether the clipboard held files.
     */
    public boolean attachFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null) return false;
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getText() != null || item.getUri() == null) return false;
            if (!ContentResolver.SCHEME_CONTENT.equals(item.getUri().getScheme())) return false;
            uris.add(item.getUri());
        }
        if (uris.isEmpty()) return false;
        attach(uris, null);
        return true;
    }

    /**
     * Copies the files, runs the hook on them and pastes the resulting paths.
     *
     * @param onRead run once the files have been read, e.g. to release a temporary permission.
     */
    public void attach(List<Uri> uris, @Nullable Runnable onRead) {
        if (uris.isEmpty() || mBusy) {
            if (mBusy) toast(R.string.e16tae_attach_busy);
            if (onRead != null) onRead.run();
            return;
        }

        final boolean hasHook = new File(HOOK_PATH).canExecute();
        mBusy = true;
        toast(hasHook ? R.string.e16tae_attach_uploading : R.string.e16tae_attach_copying);
        new Thread(() -> {
            final List<String> paths = new ArrayList<>();
            final List<File> copies = new ArrayList<>();
            String error = null;
            try {
                for (Uri uri : uris) copies.add(copyToAttachDir(uri));
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Copying failed", e);
                error = String.valueOf(e.getMessage());
            } finally {
                if (onRead != null) mMainHandler.post(onRead);
            }
            try {
                for (File local : copies) paths.add(hasHook ? runHook(local) : local.getAbsolutePath());
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Attaching failed", e);
                if (error == null) error = String.valueOf(e.getMessage());
            }
            final String fError = error;
            mMainHandler.post(() -> {
                mBusy = false;
                pastePaths(paths);
                if (fError != null)
                    Toast.makeText(mActivity, mActivity.getString(R.string.e16tae_attach_failed, fError), Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void pastePaths(List<String> paths) {
        if (paths.isEmpty()) return;
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null || session.getEmulator() == null) return;
        // One paste per path, so that a program watching for a pasted file path sees each one.
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) session.getEmulator().paste(" ");
            session.getEmulator().paste(paths.get(i));
        }
    }

    private File copyToAttachDir(Uri uri) throws IOException {
        File dir = new File(ATTACH_DIR_PATH);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + ATTACH_DIR_PATH);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        ContentResolver resolver = mActivity.getContentResolver();
        String name = safeName(displayName(uri));
        if (name.lastIndexOf('.') <= 0) {
            // Programs tell images from other files by the extension, e.g. for keyboard content.
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri));
            if (ext != null) name += "." + ext;
        }
        File target = uniqueFile(dir, stamp + "-" + name);
        try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Cannot read " + uri);
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        return target;
    }

    /** Runs the hook and returns the last line it printed. */
    private String runHook(File file) throws Exception {
        // Through sh so that the hook's shebang is resolved like in a Termux shell.
        ProcessBuilder builder = new ProcessBuilder(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/sh",
            "-c", "exec \"$0\" \"$@\"", HOOK_PATH, file.getAbsolutePath());
        builder.environment().clear();
        builder.environment().putAll(new TermuxShellEnvironment().getEnvironment(mActivity, false));
        builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        builder.redirectErrorStream(false);
        Process process = builder.start();
        process.getOutputStream().close();

        final StringBuilder stderr = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append('\n');
            } catch (IOException ignored) {
            }
        });
        errReader.start();

        String last = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) last = line.trim();
        }
        if (!process.waitFor(HOOK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException("termux-attach-hook timed out");
        }
        errReader.join(1000);
        if (process.exitValue() != 0 || last == null) {
            String err = stderr.toString().trim();
            int nl = err.lastIndexOf('\n');
            throw new IOException("termux-attach-hook failed (" + process.exitValue() + ")"
                + (err.isEmpty() ? "" : ": " + err.substring(nl + 1)));
        }
        return last;
    }

    private String displayName(Uri uri) {
        try (Cursor c = mActivity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "No display name for " + uri + ": " + e.getMessage());
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }

    /** Keeps names safe to paste unquoted: letters, digits, '.', '-' and '_'. */
    static String safeName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+", "");
        if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
        return safe.isEmpty() ? "file" : safe;
    }

    private static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; file.exists(); i++) file = new File(dir, base + "-" + i + ext);
        return file;
    }

    private void toast(int resId) {
        Toast.makeText(mActivity, resId, Toast.LENGTH_SHORT).show();
    }
}
