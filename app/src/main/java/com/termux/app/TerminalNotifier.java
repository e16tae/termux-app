package com.termux.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.terminal.TerminalNotification;
import com.termux.terminal.TerminalSession;

/**
 * e16tae fork: shows the desktop notifications that programs ask for (OSC 9, 99 and 777) as
 * Android notifications. Tapping one opens its session.
 */
public final class TerminalNotifier {

    /** Action of the intent that opens {@link TermuxActivity} from a notification. */
    public static final String ACTION_ACTIVATE = "com.termux.app.e16tae.TERMINAL_NOTIFICATION";
    public static final String EXTRA_SESSION_HANDLE = "session_handle";
    /** Text to write to the session, if the program asked to be told about the activation. */
    public static final String EXTRA_REPORT = "report";

    private static final String LOG_TAG = "TerminalNotifier";
    private static final String CHANNEL_ID = "termux_terminal_notification";
    private static final String TAG = "e16tae-terminal";

    private static int sNextAnonymousId;

    private TerminalNotifier() {
    }

    /**
     * @param sessionFocused if the session is shown and the app is in front.
     */
    public static void show(@NonNull Context context, @NonNull TerminalSession session,
                            @NonNull TerminalNotification notification, boolean sessionFocused) {
        if (notification.occasion != TerminalNotification.OCCASION_ALWAYS && sessionFocused) return;

        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationUtils.setupNotificationChannel(context, CHANNEL_ID,
                context.getString(R.string.e16tae_notification_channel), NotificationManager.IMPORTANCE_HIGH);

        String title = notification.title;
        String text = notification.body;
        if (title.isEmpty()) {
            title = sessionLabel(session);
        }

        int id = notificationId(session, notification.id);
        Intent intent = new Intent(context, TermuxActivity.class)
            .setAction(ACTION_ACTIVATE)
            .putExtra(EXTRA_SESSION_HANDLE, session.mHandle)
            .putExtra(EXTRA_REPORT, notification.activationReport())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, intent, flags);

        int priority = notification.urgency == TerminalNotification.URGENCY_LOW ? Notification.PRIORITY_LOW
            : notification.urgency == TerminalNotification.URGENCY_CRITICAL ? Notification.PRIORITY_MAX
            : Notification.PRIORITY_HIGH;
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(context, CHANNEL_ID, priority,
            title, text, text.isEmpty() ? null : text, contentIntent, null, NotificationUtils.NOTIFICATION_MODE_ALL);
        if (builder == null) return;
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setAutoCancel(true);
        builder.setShowWhen(true);
        if (notification.urgency == TerminalNotification.URGENCY_CRITICAL) builder.setCategory(Notification.CATEGORY_ALARM);
        try {
            manager.notify(TAG, id, builder.build());
        } catch (Exception e) {
            // E.g. notifications are not allowed.
            Logger.logStackTraceWithMessage(LOG_TAG, "Cannot show terminal notification", e);
        }
    }

    public static void close(@NonNull Context context, @NonNull TerminalSession session, @NonNull String id) {
        if (id.isEmpty()) return;
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager != null) manager.cancel(TAG, notificationId(session, id));
    }

    /** Notifications with the same id from the same session replace each other; those without an id never do. */
    private static synchronized int notificationId(TerminalSession session, String id) {
        if (id.isEmpty()) return 0x40000000 | (sNextAnonymousId++ & 0x3fffffff);
        return (session.mHandle + ":" + id).hashCode() & 0x3fffffff;
    }

    @NonNull
    private static String sessionLabel(TerminalSession session) {
        String name = session.mSessionName;
        if (name != null && !name.isEmpty()) return name;
        String title = session.getTitle();
        if (title != null && !title.isEmpty()) return title;
        return "Termux";
    }

    /**
     * Handles the intent of a tapped notification: switches to its session and writes the
     * activation report the program asked for.
     *
     * @return whether the intent came from a notification.
     */
    public static boolean onActivated(@Nullable Intent intent, @NonNull TermuxActivity activity) {
        if (intent == null || !ACTION_ACTIVATE.equals(intent.getAction())) return false;
        TermuxService service = activity.getTermuxService();
        if (service == null) return true;
        TerminalSession session = service.getTerminalSessionForHandle(intent.getStringExtra(EXTRA_SESSION_HANDLE));
        if (session == null) return true;
        activity.getTermuxTerminalSessionClient().setCurrentSession(session);
        String report = intent.getStringExtra(EXTRA_REPORT);
        if (report != null && session.isRunning()) session.write(report);
        return true;
    }
}
