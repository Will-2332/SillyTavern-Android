package com.sillytavern.android;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Debug-only: in-memory log of recent hook/notification events, shown in the launcher settings. */
final class DiagLog {
    private static final int MAX = 150;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private DiagLog() {}

    static synchronized void add(String event) {
        if (lines.size() >= MAX) lines.removeFirst();
        lines.addLast(fmt.format(new Date()) + " " + event);
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.length() == 0 ? "(no events yet)" : sb.toString();
    }

    static synchronized void clear() {
        lines.clear();
    }
}
