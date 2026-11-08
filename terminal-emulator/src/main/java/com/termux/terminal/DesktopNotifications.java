package com.termux.terminal;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses the desktop notification escape sequences OSC 9, OSC 777 and OSC 99. */
final class DesktopNotifications {

    private static final int MAX_TEXT_LENGTH = 1024;
    /** OSC 99 notifications that are still being sent in chunks ({@code d=0}). */
    private static final int MAX_PENDING = 8;
    /** ConEmu uses "OSC 9 ; <number> ; ..." for other things, e.g. "9;4;1;50" for progress. */
    private static final Pattern OSC9_SUBCOMMAND = Pattern.compile("^[0-9]+(;|$)");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_+.-]{0,64}$");

    private static final class Pending {
        final StringBuilder title = new StringBuilder();
        final StringBuilder body = new StringBuilder();
        boolean focus = true;
        boolean report = false;
        int occasion = TerminalNotification.OCCASION_ALWAYS;
        int urgency = TerminalNotification.URGENCY_NORMAL;
    }

    private final TerminalOutput mOutput;
    private final Map<String, Pending> mPending = new LinkedHashMap<>();

    DesktopNotifications(TerminalOutput output) {
        mOutput = output;
    }

    /** OSC 9 ; message (iTerm2). */
    void onOsc9(String text) {
        if (text.isEmpty() || OSC9_SUBCOMMAND.matcher(text).find()) return;
        mOutput.onDesktopNotification(new TerminalNotification("", "", clip(text), true, false,
            TerminalNotification.OCCASION_ALWAYS, TerminalNotification.URGENCY_NORMAL));
    }

    /** OSC 777 ; notify ; title ; body (rxvt-unicode, foot, Ghostty and others). */
    void onOsc777(String text) {
        String[] parts = text.split(";", 3);
        if (parts.length < 2 || !"notify".equals(parts[0])) return;
        String body = parts.length > 2 ? parts[2] : "";
        if (parts[1].isEmpty() && body.isEmpty()) return;
        mOutput.onDesktopNotification(new TerminalNotification("", clip(parts[1]), clip(body), true, false,
            TerminalNotification.OCCASION_ALWAYS, TerminalNotification.URGENCY_NORMAL));
    }

    /** OSC 99 ; metadata ; payload (kitty). */
    void onOsc99(String text) {
        int semicolon = text.indexOf(';');
        if (semicolon < 0) return;
        String metadata = text.substring(0, semicolon);
        String payload = text.substring(semicolon + 1);

        String id = "";
        boolean done = true;
        String type = "title";
        boolean base64 = false;
        String actions = null, occasion = null, urgency = null;
        for (String keyValue : metadata.split(":")) {
            int equals = keyValue.indexOf('=');
            if (equals <= 0) continue;
            String value = keyValue.substring(equals + 1);
            switch (keyValue.substring(0, equals)) {
                case "i": id = value; break;
                case "d": done = !"0".equals(value); break;
                case "p": type = value; break;
                case "e": base64 = "1".equals(value); break;
                case "a": actions = value; break;
                case "o": occasion = value; break;
                case "u": urgency = value; break;
                default: break; // Icons, buttons, sounds, expiry, ... are not supported.
            }
        }
        if (!ID.matcher(id).matches()) return;

        if ("?".equals(type)) {
            mOutput.write("\033]99;i=" + id + ":p=?;a=focus,report:o=always,unfocused,invisible:u=0,1,2:p=title,body,?,close\033\\");
            return;
        }
        if ("close".equals(type)) {
            mPending.remove(id);
            if (!id.isEmpty()) mOutput.onDesktopNotificationClosed(id);
            return;
        }

        if (base64) {
            try {
                payload = new String(Base64.decode(payload, Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return;
            }
        }

        Pending pending = mPending.get(id);
        if (pending == null) {
            if (mPending.size() >= MAX_PENDING) {
                Iterator<String> eldest = mPending.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
            pending = new Pending();
            mPending.put(id, pending);
        }
        if (actions != null) {
            for (String action : actions.split(",")) {
                boolean on = !action.startsWith("-");
                switch (on ? action : action.substring(1)) {
                    case "focus": pending.focus = on; break;
                    case "report": pending.report = on; break;
                    default: break;
                }
            }
        }
        if (occasion != null) {
            switch (occasion) {
                case "unfocused": pending.occasion = TerminalNotification.OCCASION_UNFOCUSED; break;
                case "invisible": pending.occasion = TerminalNotification.OCCASION_INVISIBLE; break;
                default: pending.occasion = TerminalNotification.OCCASION_ALWAYS; break;
            }
        }
        if (urgency != null) {
            switch (urgency) {
                case "0": pending.urgency = TerminalNotification.URGENCY_LOW; break;
                case "2": pending.urgency = TerminalNotification.URGENCY_CRITICAL; break;
                default: pending.urgency = TerminalNotification.URGENCY_NORMAL; break;
            }
        }
        if ("title".equals(type)) append(pending.title, payload);
        else if ("body".equals(type)) append(pending.body, payload);

        if (!done) return;
        mPending.remove(id);
        if (pending.title.length() == 0 && pending.body.length() == 0) return;
        mOutput.onDesktopNotification(new TerminalNotification(id, pending.title.toString(), pending.body.toString(),
            pending.focus, pending.report, pending.occasion, pending.urgency));
    }

    private static void append(StringBuilder builder, String text) {
        int room = MAX_TEXT_LENGTH - builder.length();
        if (room > 0) builder.append(text, 0, Math.min(room, text.length()));
    }

    private static String clip(String text) {
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
    }
}
