package dev.itemloom.compat.sx;

import java.text.SimpleDateFormat;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Branch selection and calendar offsets used by SX text expressions. */
final class SxBranchTime {
    private static final Pattern SECONDS = Pattern.compile("[0-9]+");
    private static final Pattern UNITS = Pattern.compile("[YyMDdHhmSs]");
    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");

    private SxBranchTime() {}

    static String branch(String input) {
        int separator = input.indexOf('$');
        if (separator < 0) {
            return "";
        }
        String match = input.substring(0, separator);
        List<Branch> alternatives = decodeBranches(input.substring(separator + 1));
        for (Branch alternative : alternatives) {
            if (alternative.label().equals(match)) {
                return alternative.value();
            }
        }
        for (int index = alternatives.size() - 1; index >= 0; index--) {
            Branch alternative = alternatives.get(index);
            if (alternative.label().equals("default") || alternative.label().equals("else")) {
                return alternative.value();
            }
        }
        return "";
    }

    private static List<Branch> decodeBranches(String input) {
        List<Branch> result = new ArrayList<>();
        for (String alternative : input.split("\\|", -1)) {
            int colon = alternative.indexOf(':');
            if (colon >= 0) {
                result.add(
                        new Branch(
                                alternative.substring(0, colon), alternative.substring(colon + 1)));
            }
        }
        return result;
    }

    static String time(String input, String format, Clock clock) {
        Calendar target = Calendar.getInstance(TimeZone.getTimeZone(clock.getZone()));
        target.setTimeInMillis(clock.millis());
        if (SECONDS.matcher(input).matches()) {
            long milliseconds = Math.multiplyExact(Long.parseLong(input), 1000L);
            target.setTimeInMillis(Math.addExact(target.getTimeInMillis(), milliseconds));
        } else {
            for (CalendarOffset offset : decodeOffsets(input)) {
                target.add(offset.field(), offset.amount());
            }
        }
        SimpleDateFormat formatter = new SimpleDateFormat(format);
        formatter.setTimeZone(target.getTimeZone());
        return formatter.format(target.getTime());
    }

    private static List<CalendarOffset> decodeOffsets(String input) {
        List<CalendarOffset> result = new ArrayList<>();
        Matcher units = UNITS.matcher(input);
        int amountStart = 0;
        while (units.find()) {
            int amount = amount(input.substring(amountStart, units.start()));
            result.add(new CalendarOffset(calendarField(input.charAt(units.start())), amount));
            amountStart = units.end();
        }
        // An unused trailing amount must still fit the input language's integer range.
        amount(input.substring(amountStart));
        return result;
    }

    private static int amount(String input) {
        String digits = NON_DIGITS.matcher(input).replaceAll("");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private static int calendarField(char character) {
        return switch (character) {
            case 'Y', 'y' -> Calendar.YEAR;
            case 'M' -> Calendar.MONTH;
            case 'D', 'd' -> Calendar.DAY_OF_MONTH;
            case 'H', 'h' -> Calendar.HOUR_OF_DAY;
            case 'm' -> Calendar.MINUTE;
            case 'S', 's' -> Calendar.SECOND;
            default ->
                    throw new IllegalArgumentException("Unsupported calendar unit: " + character);
        };
    }

    private record Branch(String label, String value) {}

    private record CalendarOffset(int field, int amount) {}
}
