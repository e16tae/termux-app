package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A desktop notification that a program asked the terminal to show, with OSC 9 (iTerm2),
 * OSC 777 (rxvt "notify") or OSC 99 (kitty, https://sw.kovidgoyal.net/kitty/desktop-notifications/).
 */
public final class TerminalNotification {

    /** Show the notification even if the terminal has focus. The default. */
    public static final int OCCASION_ALWAYS = 0;
    /** Show it only if the terminal does not have focus. */
    public static final int OCCASION_UNFOCUSED = 1;
    /** Show it only if the terminal is not visible. */
    public static final int OCCASION_INVISIBLE = 2;

    public static final int URGENCY_LOW = 0;
    public static final int URGENCY_NORMAL = 1;
    public static final int URGENCY_CRITICAL = 2;

    /** The identifier the program gave (OSC 99 {@code i=}), or {@code ""} if none. */
    @NonNull public final String id;
    /** The title, may be {@code ""}. */
    @NonNull public final String title;
    /** The body, may be {@code ""}. */
    @NonNull public final String body;
    /** If the terminal should come to the front when the notification is activated. */
    public final boolean focusOnActivation;
    /** If the program wants to be told when the notification is activated. */
    public final boolean reportActivation;
    /** One of the {@code OCCASION_*} values. */
    public final int occasion;
    /** One of the {@code URGENCY_*} values. */
    public final int urgency;

    public TerminalNotification(@NonNull String id, @NonNull String title, @NonNull String body,
                                boolean focusOnActivation, boolean reportActivation, int occasion, int urgency) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.focusOnActivation = focusOnActivation;
        this.reportActivation = reportActivation;
        this.occasion = occasion;
        this.urgency = urgency;
    }

    /**
     * The text to write to the program when the notification is activated, or {@code null} if it
     * did not ask for that.
     */
    @Nullable
    public String activationReport() {
        if (!reportActivation || id.isEmpty()) return null;
        return "\033]99;i=" + id + ";\033\\";
    }

    @NonNull
    @Override
    public String toString() {
        return "TerminalNotification{id='" + id + "', title='" + title + "', body='" + body + "'}";
    }
}
