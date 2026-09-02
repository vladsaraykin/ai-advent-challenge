package com.github.vladsaraykin.aichat.reasoning;

import java.util.Locale;

final class DurationFormatter {

    private DurationFormatter() {
    }

    static String format(long millis) {
        if (millis < 1_000) {
            return millis + " мс";
        }
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%.1f с", millis / 1_000.0);
        }
        long minutes = millis / 60_000;
        double seconds = (millis % 60_000) / 1_000.0;
        return String.format(Locale.ROOT, "%d мин %.1f с", minutes, seconds);
    }
}
