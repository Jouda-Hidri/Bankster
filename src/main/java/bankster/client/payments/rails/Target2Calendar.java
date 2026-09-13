package bankster.client.payments.rails;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

/**
 * The TARGET2 settlement calendar.
 *
 * <p>Euro clearing runs on TARGET days, which are weekdays other than six fixed
 * closing days. It matters because a standard SEPA credit transfer instructed on
 * a Friday afternoon does not reach the beneficiary until Monday, and one
 * instructed on the 24th of December may not arrive until the 27th. Quoting
 * "next business day" without the calendar produces value dates that are simply
 * wrong, and value dates are what interest and liquidity are computed on.
 *
 * <p>Two of the closing days move: Good Friday and Easter Monday. Easter is
 * computed with the anonymous Gregorian algorithm rather than tabulated, so the
 * calendar stays correct in future years without anyone maintaining a list.
 */
public final class Target2Calendar {

    /** The fixed TARGET closing days. */
    private static final Set<MonthDay> FIXED_CLOSURES = Set.of(
            MonthDay.of(1, 1),    // New Year's Day
            MonthDay.of(5, 1),    // Labour Day
            MonthDay.of(12, 25),  // Christmas Day
            MonthDay.of(12, 26)); // Boxing Day

    private Target2Calendar() {
    }

    public static boolean isSettlementDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        if (FIXED_CLOSURES.contains(MonthDay.from(date))) {
            return false;
        }
        LocalDate easter = easterSunday(date.getYear());
        return !date.equals(easter.minusDays(2)) && !date.equals(easter.plusDays(1));
    }

    /** The next settlement day strictly after {@code date}. */
    public static LocalDate nextSettlementDay(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (!isSettlementDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    /** {@code date} itself when it is a settlement day, otherwise the next one. */
    public static LocalDate thisOrNextSettlementDay(LocalDate date) {
        LocalDate candidate = date;
        while (!isSettlementDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    /** Easter Sunday, by the anonymous Gregorian computus. */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
