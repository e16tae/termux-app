package com.termux.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * e16tae fork: checks the fork's GitHub releases and installs a newer release APK through
 * {@link PackageInstaller}. Android still shows its own confirmation screen, since this app
 * targets API 28 and cannot install silently.
 */
public final class AppUpdater {

    public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, FAILED }

    public interface Listener {
        void onUpdateStateChanged(State state, String latestTag, int progressPercent);
    }

    private static final String LOG_TAG = "AppUpdater";
    private static final String RELEASES_API = "https://api.github.com/repos/e16tae/termux-app/releases/latest";
    private static final String DEV_RELEASE_API = "https://api.github.com/repos/e16tae/termux-app/releases/tags/dev-latest";
    private static final long AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final String ACTION_INSTALL_RESULT = "com.termux.app.e16tae.INSTALL_RESULT";
    // Release builds are "<upstream>+e16tae.<n>", dev builds "<upstream>+e16tae.<n>.dev.<run>.<sha>"
    // where n is the last release. Builds from before releases were numbered this way
    // ("+e16tae.<run>.<sha>") do not match and count as 0.
    private static final Pattern INSTALLED_BUILD = Pattern.compile("\\+e16tae\\.(\\d+)(\\.dev\\..+)?$");
    private static final Pattern INSTALLED_DEV_SHA = Pattern.compile("\\.dev\\.\\d+\\.([0-9a-f]{7,})$");
    private static final Pattern RELEASE_BUILD = Pattern.compile("^v\\d+\\.\\d+\\.\\d+-e16tae\\.(\\d+)$");

    private static AppUpdater sInstance;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Listener mListener;
    private State mState = State.IDLE;
    private String mLatestTag;
    private String mApkUrl;
    private String mApkName;
    private String mSumsUrl;
    private int mProgress;
    private long mLastCheck;
    /** The state to go back to when a download or install fails or is cancelled. */
    private State mStateAfterFailure = State.AVAILABLE;

    /** The APK and checksum assets of a release. */
    private static final class Assets {
        String apkUrl, apkName, sumsUrl;

        static Assets of(JSONObject release) throws Exception {
            Assets a = new Assets();
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                String url = asset.getString("browser_download_url");
                if (name.equals("SHA256SUMS")) a.sumsUrl = url;
                else if (name.endsWith("_universal.apk")) { a.apkUrl = url; a.apkName = name; }
            }
            return a.apkUrl != null && a.sumsUrl != null ? a : null;
        }
    }

    /** A non-200 HTTP response. */
    private static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }
    }

    public static synchronized AppUpdater get(Context context) {
        if (sInstance == null) sInstance = new AppUpdater(context.getApplicationContext());
        return sInstance;
    }

    private AppUpdater(Context context) {
        mContext = context;
    }

    public void setListener(Listener listener) {
        mListener = listener;
        if (listener != null) listener.onUpdateStateChanged(mState, mLatestTag, mProgress);
    }

    public void clearListener(Listener listener) {
        if (mListener == listener) mListener = null;
    }

    /** The button action: check, or download and install when a newer release is known. */
    public void onButtonClicked() {
        switch (mState) {
            case CHECKING:
            case DOWNLOADING:
            case INSTALLING:
                return;
            case AVAILABLE:
                mStateAfterFailure = State.AVAILABLE;
                downloadAndInstall(mApkUrl, mApkName, mSumsUrl);
                return;
            default:
                check(true);
        }
    }

    /** Long press: install the dev-latest pre-release, for testing. */
    public void onButtonLongClicked() {
        if (mState == State.CHECKING || mState == State.DOWNLOADING || mState == State.INSTALLING) return;
        final State before = mState;
        setState(State.CHECKING, 0);
        new Thread(() -> {
            try {
                JSONObject release = new JSONObject(readUrl(DEV_RELEASE_API));
                String commit = release.optString("target_commitish", "");
                Assets assets = Assets.of(release);
                if (assets == null) throw new IOException("No APK in dev-latest");
                Matcher m = INSTALLED_DEV_SHA.matcher(BuildConfig.VERSION_NAME);
                boolean installed = m.find() && commit.startsWith(m.group(1));
                mMainHandler.post(() -> {
                    if (installed) {
                        setState(before, 0);
                        toast(mContext.getString(R.string.e16tae_update_dev_installed));
                        return;
                    }
                    toast(mContext.getString(R.string.e16tae_update_dev_downloading));
                    mStateAfterFailure = before;
                    downloadAndInstall(assets.apkUrl, assets.apkName, assets.sumsUrl);
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Dev build check failed", e);
                mMainHandler.post(() -> {
                    setState(before, 0);
                    toast(mContext.getString(R.string.e16tae_update_check_failed));
                });
            }
        }).start();
    }

    /** Checks silently unless a check succeeded in the last few hours. */
    public void checkIfStale() {
        if (mState == State.CHECKING || mState == State.DOWNLOADING || mState == State.INSTALLING) return;
        if ((mState == State.UP_TO_DATE || mState == State.AVAILABLE)
            && System.currentTimeMillis() - mLastCheck < AUTO_CHECK_INTERVAL_MS) return;
        check(false);
    }

    private void check(boolean userRequested) {
        setState(State.CHECKING, 0);
        new Thread(() -> {
            try {
                JSONObject release = new JSONObject(readUrl(RELEASES_API));
                String tag = release.getString("tag_name");
                Assets assets = Assets.of(release);
                final String fApkUrl = assets != null ? assets.apkUrl : null;
                final String fApkName = assets != null ? assets.apkName : null;
                final String fSumsUrl = assets != null ? assets.sumsUrl : null;
                final boolean newer = releaseNumber(tag) > installedNumber() && assets != null;
                mMainHandler.post(() -> {
                    mLastCheck = System.currentTimeMillis();
                    mLatestTag = tag;
                    mApkUrl = fApkUrl;
                    mApkName = fApkName;
                    mSumsUrl = fSumsUrl;
                    if (newer) {
                        setState(State.AVAILABLE, 0);
                        if (!userRequested) toast(mContext.getString(R.string.e16tae_update_available_toast, shortTag(tag)));
                    } else {
                        setState(State.UP_TO_DATE, 0);
                        if (userRequested) toast(mContext.getString(R.string.e16tae_update_latest_toast, installedLabel()));
                    }
                });
            } catch (HttpStatusException e) {
                if (e.code != HttpURLConnection.HTTP_NOT_FOUND) { checkFailed(e, userRequested); return; }
                // No release has been published yet.
                mMainHandler.post(() -> {
                    mLastCheck = System.currentTimeMillis();
                    mLatestTag = null;
                    setState(State.UP_TO_DATE, 0);
                    if (userRequested) toast(mContext.getString(R.string.e16tae_update_no_release_toast));
                });
            } catch (Exception e) {
                checkFailed(e, userRequested);
            }
        }).start();
    }

    private void checkFailed(Exception e, boolean userRequested) {
        Logger.logStackTraceWithMessage(LOG_TAG, "Update check failed", e);
        mMainHandler.post(() -> {
            setState(State.FAILED, 0);
            if (userRequested) toast(mContext.getString(R.string.e16tae_update_check_failed));
        });
    }

    private void downloadAndInstall(String apkUrl, String apkName, String sumsUrl) {
        setState(State.DOWNLOADING, 0);
        new Thread(() -> {
            File dir = new File(mContext.getCacheDir(), "e16tae-update");
            File apk = new File(dir, "update.apk");
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
                String expected = expectedSha256(readUrl(sumsUrl), apkName);
                if (expected == null) throw new IOException("No checksum for " + apkName);
                String actual = download(apkUrl, apk);
                if (!expected.equalsIgnoreCase(actual)) throw new IOException("Checksum mismatch");
                mMainHandler.post(() -> setState(State.INSTALLING, 100));
                install(apk);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Update download/install failed", e);
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                mMainHandler.post(() -> {
                    setState(mStateAfterFailure, 0);
                    toast(mContext.getString(R.string.e16tae_update_install_failed, String.valueOf(e.getMessage())));
                });
            }
        }).start();
    }

    private void install(File apk) throws IOException {
        PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(mContext.getPackageName());
        params.setSize(apk.length());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                session.fsync(out);
            }
            Intent intent = new Intent(mContext, InstallResultReceiver.class).setAction(ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, sessionId, intent, flags);
            session.commit(pendingIntent.getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        }
    }

    void onInstallResult(int status, String message, Intent confirmIntent) {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && confirmIntent != null) {
            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mContext.startActivity(confirmIntent);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Cannot open install confirmation", e);
                setState(mStateAfterFailure, 0);
                toast(mContext.getString(R.string.e16tae_update_install_failed, String.valueOf(e.getMessage())));
            }
            return;
        }
        new File(new File(mContext.getCacheDir(), "e16tae-update"), "update.apk").delete();
        if (status == PackageInstaller.STATUS_SUCCESS) {
            setState(State.UP_TO_DATE, 0);
            return;
        }
        // Cancelled on the confirmation screen, or failed: allow trying again.
        setState(mStateAfterFailure, 0);
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED)
            toast(mContext.getString(R.string.e16tae_update_install_failed, String.valueOf(message)));
    }

    public static final class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_INSTALL_RESULT.equals(intent.getAction())) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            AppUpdater.get(context).onInstallResult(status, message, confirm);
        }
    }

    public String installedLabel() {
        Matcher m = INSTALLED_BUILD.matcher(BuildConfig.VERSION_NAME);
        if (!m.find()) return BuildConfig.VERSION_NAME;
        return "e16tae." + m.group(1) + (m.group(2) != null ? " dev" : "");
    }

    /** "v0.118.0-e16tae.3" → "e16tae.3" for the drawer button. */
    public static String shortTag(String tag) {
        return tag == null ? "" : tag.replaceFirst("^v[0-9.]+-", "");
    }

    private static int installedNumber() {
        Matcher m = INSTALLED_BUILD.matcher(BuildConfig.VERSION_NAME);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static int releaseNumber(String tag) {
        Matcher m = RELEASE_BUILD.matcher(tag);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static String expectedSha256(String sums, String fileName) {
        for (String line : sums.split("\n")) {
            String[] parts = line.trim().split("\\s+\\*?", 2);
            if (parts.length == 2 && parts[1].trim().equals(fileName)) return parts[0];
        }
        return null;
    }

    private void setState(State state, int progress) {
        mState = state;
        mProgress = progress;
        if (mListener != null) mListener.onUpdateStateChanged(state, mLatestTag, progress);
    }

    private void toast(String text) {
        Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
    }

    private static HttpURLConnection open(String url) throws IOException {
        boolean api = url.startsWith("https://api.github.com/");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "e16tae-termux-updater");
        if (api) connection.setRequestProperty("Accept", "application/vnd.github+json");
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new HttpStatusException(code, url);
        }
        return connection;
    }

    private static String readUrl(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            connection.disconnect();
        }
    }

    /** Downloads to {@code target} and returns its SHA-256 hex digest. */
    private String download(String url, File target) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            long total = connection.getContentLength();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long done = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                done += n;
                if (total > 0) {
                    final int percent = (int) (done * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        mMainHandler.post(() -> setState(State.DOWNLOADING, percent));
                    }
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } finally {
            connection.disconnect();
        }
    }
}
