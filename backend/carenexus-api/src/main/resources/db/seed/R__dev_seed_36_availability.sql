-- ============================================================
-- DEV ONLY (local and docker profiles): weekly working hours for the seeded doctors.
--
-- Every doctor in `doctors` that has no stored availability block gets, for each of the seven weekdays, two active
-- blocks 08:00-13:00 and 14:00-18:00 with 30-minute slots (the default clinic hours with a lunch break). No time off
-- is seeded. A doctor who already has a template (saved through the API) is left alone.
--
-- Repeatable and idempotent: a re-run inserts nothing for doctors that already have blocks, and the unique key
-- (doctor_system_id, day_of_week, start_time) guards against duplicates. With no doctors it inserts nothing.
-- ============================================================
INSERT INTO doctor_availability (doctor_system_id, day_of_week, start_time, end_time, slot_minutes, is_active,
                                 created_at, updated_at)
SELECT d.system_id, dow.day_of_week, blk.start_time, blk.end_time, 30, TRUE,
       date_trunc('second', LOCALTIMESTAMP), date_trunc('second', LOCALTIMESTAMP)
  FROM doctors d
  JOIN users u ON u.system_id = d.system_id
 CROSS JOIN generate_series(1, 7) AS dow(day_of_week)
 CROSS JOIN (VALUES (TIME '08:00', TIME '13:00'),
                    (TIME '14:00', TIME '18:00')) AS blk(start_time, end_time)
 WHERE NOT EXISTS (SELECT 1 FROM doctor_availability a WHERE a.doctor_system_id = d.system_id)
ON CONFLICT (doctor_system_id, day_of_week, start_time) DO NOTHING;
