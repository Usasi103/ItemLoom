package dev.itemloom.compat.sx;

import java.text.SimpleDateFormat;
import java.time.Clock;
import java.util.Calendar;
import java.util.TimeZone;

final class SxBranchTime {
    static String branch(String input) {
        int start = input.indexOf('$');
        if (start < 0) {
            return "";
        }
        String wanted = input.substring(0, start);
        String fallback = "";
        for (String alternative : input.substring(start + 1).split("\\|", -1)) {
            int colon = alternative.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = alternative.substring(0, colon);
            String value = alternative.substring(colon + 1);
            if (label.equals(wanted)) {
                return value;
            }
            if (label.equals("default") || label.equals("else")) {
                fallback = value;
            }
        }
        return fallback;
    }

    static String time(String input, String format, Clock clock) {
        TimeZone zone = TimeZone.getTimeZone(clock.getZone());
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(clock.millis());
        if (input.matches("[0-9]+")) {
            long milliseconds = Math.multiplyExact(Long.parseLong(input), 1000L);
            calendar.setTimeInMillis(Math.addExact(calendar.getTimeInMillis(), milliseconds));
        } else {
            StringBuilder digits = new StringBuilder();
            for (int index = 0; index < input.length(); index++) {
                char letter = input.charAt(index);
                if (letter >= '0' && letter <= '9') {
                    digits.append(letter);
                    continue;
                }
                int unit =
                        switch (letter) {
                            case 'Y', 'y' -> Calendar.YEAR;
                            case 'M' -> Calendar.MONTH;
                            case 'D', 'd' -> Calendar.DAY_OF_MONTH;
                            case 'H', 'h' -> Calendar.HOUR_OF_DAY;
                            case 'm' -> Calendar.MINUTE;
                            case 'S', 's' -> Calendar.SECOND;
                            default -> -1;
                        };
                if (unit >= 0) {
                    calendar.add(unit, count(digits));
                    digits.setLength(0);
                }
            }
            count(digits);
        }
        SimpleDateFormat formatter = new SimpleDateFormat(format);
        formatter.setTimeZone(zone);
        return formatter.format(calendar.getTime());
    }

    private static int count(StringBuilder digits) {
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }
}
