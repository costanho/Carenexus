package com.carenexus.api.medication;

import com.carenexus.api.medication.service.ScheduleTimes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normalising daily times and deriving them from a free-text frequency (contract be-medication 3.3). */
class ScheduleTimesTest {

    private static List<String> derive(String frequency) {
        return ScheduleTimes.format(ScheduleTimes.deriveFromFrequency(frequency));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Once daily|08:00",
            "once a day|08:00",
            "Daily|08:00",
            "every day|08:00",
            "Once daily at night|20:00",
            "Nightly|20:00",
            "once daily at bedtime|20:00",
            "1x per day|08:00",
            "Twice daily|08:00,20:00",
            "2 times a day|08:00,20:00",
            "BD|08:00,20:00",
            "bid|08:00,20:00",
            "Three times daily|08:00,14:00,20:00",
            "thrice daily|08:00,14:00,20:00",
            "TDS|08:00,14:00,20:00",
            "tid after meals|08:00,14:00,20:00",
            "Four times daily|08:00,12:00,16:00,20:00",
            "4x/day|08:00,12:00,16:00,20:00",
            "QID|08:00,12:00,16:00,20:00",
            "qds|08:00,12:00,16:00,20:00",
            "OD|08:00",
            "  Twice   DAILY  |08:00,20:00"
    })
    void derivesDailyTimes(String frequency, String expected) {
        assertEquals(Arrays.asList(expected.split(",")), derive(frequency));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Once weekly", "twice a week", "monthly", "as needed", "PRN", "bd prn",
            "alternate days", "every other day", "every 8 hours", "with meals", "", "   "})
    void noPlanWhenNoDailyCountCanBeRead(String frequency) {
        assertTrue(derive(frequency).isEmpty(), frequency);
    }

    @Test
    void nullFrequencyGivesNoPlan() {
        assertTrue(ScheduleTimes.deriveFromFrequency(null).isEmpty());
    }

    @Test
    void normaliseRemovesDuplicatesSortsAndDropsInvalidEntries() {
        List<LocalTime> times = ScheduleTimes.normalise(Arrays.asList("20:00", "08:00", "20:00", null, "8:00", "24:00"));
        assertEquals(List.of("08:00", "20:00"), ScheduleTimes.format(times));
    }

    @Test
    void jsonRoundTrip() {
        List<LocalTime> times = ScheduleTimes.normalise(List.of("13:00", "07:30"));
        String json = ScheduleTimes.toJson(times);
        assertEquals("[\"07:30\",\"13:00\"]", json);
        assertEquals(times, ScheduleTimes.fromJson(json));
        assertTrue(ScheduleTimes.fromJson("not json").isEmpty());
        assertTrue(ScheduleTimes.fromJson(null).isEmpty());
    }

    @Test
    void validatesHhMm() {
        assertTrue(ScheduleTimes.isValid("00:00"));
        assertTrue(ScheduleTimes.isValid("23:59"));
        assertFalse(ScheduleTimes.isValid("8:00"));
        assertFalse(ScheduleTimes.isValid("08:00:00"));
        assertFalse(ScheduleTimes.isValid("24:00"));
        assertFalse(ScheduleTimes.isValid("8am"));
    }
}
