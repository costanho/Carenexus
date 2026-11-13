-- ============================================================
-- DEV ONLY (local and docker profiles): demo vitals and symptom logs for the health-tracking screens.
--
-- For the demo patient linda.moyo@carenexus.com:
--   * four measurement sessions she entered herself over the last week (blood pressure + heart rate), one of
--     them with an elevated blood pressure (ABNORMAL);
--   * weight and height entered by the demo doctor james.banda@carenexus.com, so the derived BMI is shown;
--   * one MILD symptom log she wrote two days ago.
-- Severity and is_abnormal follow the default adult reference ranges of the health module.
--
-- Repeatable and idempotent: every reading group has a fixed UUID (prefix 0047) and the symptom log a fixed note
-- marker; rows that already exist are not inserted again. Skipped when the demo users are missing.
-- Creates no notification and publishes no event (dev data only).
-- ============================================================
DO $$
DECLARE
    v_patient VARCHAR(20);
    v_doctor  VARCHAR(20);
    v_now     TIMESTAMP := date_trunc('minute', LOCALTIMESTAMP);
BEGIN
    SELECT u.system_id INTO v_patient
      FROM users u
      JOIN patients p ON p.system_id = u.system_id
     WHERE u.email = 'linda.moyo@carenexus.com' AND u.role = 'PATIENT';

    SELECT u.system_id INTO v_doctor
      FROM users u
      JOIN doctors d ON d.system_id = u.system_id
     WHERE u.email = 'james.banda@carenexus.com' AND u.role = 'DOCTOR';

    IF v_patient IS NULL OR v_doctor IS NULL THEN
        RAISE NOTICE 'dev seed 47 (health): demo patient or demo doctor not found, nothing inserted';
        RETURN;
    END IF;

    -- Patient-entered sessions: (group, days ago, systolic, diastolic, heart rate)
    INSERT INTO health_metrics (patient_system_id, recorded_by_system_id, reading_group_id, type, value, unit,
                                source, device_id, is_abnormal, severity, notes, recorded_at)
    SELECT v_patient, v_patient, s.grp, r.type, r.value, r.unit, 'MANUAL', NULL,
           r.severity <> 'NORMAL', r.severity, NULL, v_now - make_interval(days => s.days_ago, hours => 2)
      FROM (VALUES
              ('00470000-0000-4000-8000-000000000001', 6, 128, 82, 72),
              ('00470000-0000-4000-8000-000000000002', 4, 134, 86, 75),
              ('00470000-0000-4000-8000-000000000003', 2, 131, 84, 70),
              ('00470000-0000-4000-8000-000000000004', 0, 146, 91, 78)
           ) AS s(grp, days_ago, sys, dia, hr)
     CROSS JOIN LATERAL (VALUES
              ('BP_SYSTOLIC',  s.sys::NUMERIC, 'mmHg', CASE WHEN s.sys > 139 THEN 'ABNORMAL' ELSE 'NORMAL' END),
              ('BP_DIASTOLIC', s.dia::NUMERIC, 'mmHg', CASE WHEN s.dia > 89 THEN 'ABNORMAL' ELSE 'NORMAL' END),
              ('HEART_RATE',   s.hr::NUMERIC,  'bpm',  'NORMAL')
           ) AS r(type, value, unit, severity)
     WHERE NOT EXISTS (SELECT 1 FROM health_metrics hm WHERE hm.reading_group_id = s.grp);

    -- Doctor-entered weight and height (BMI 71.5 / 1.72^2 = 24.2, NORMAL)
    INSERT INTO health_metrics (patient_system_id, recorded_by_system_id, reading_group_id, type, value, unit,
                                source, device_id, is_abnormal, severity, notes, recorded_at)
    SELECT v_patient, v_doctor, '00470000-0000-4000-8000-000000000005', r.type, r.value, r.unit, 'MANUAL', NULL,
           FALSE, 'NORMAL', NULL, v_now - INTERVAL '5 days'
      FROM (VALUES ('WEIGHT', 71.5::NUMERIC, 'kg'), ('HEIGHT', 172::NUMERIC, 'cm')) AS r(type, value, unit)
     WHERE NOT EXISTS (SELECT 1 FROM health_metrics hm
                        WHERE hm.reading_group_id = '00470000-0000-4000-8000-000000000005');

    -- One mild symptom log written by the patient two days ago
    INSERT INTO symptom_logs (patient_system_id, logged_by_system_id, symptoms, severity, duration_days, notes,
                              source, logged_at, is_deleted)
    SELECT v_patient, v_patient, '["Headache", "Tiredness"]'::jsonb, 'MILD', 1,
           'Demo log (dev seed 47): better after rest', 'PATIENT_REPORTED', v_now - INTERVAL '2 days', FALSE
     WHERE NOT EXISTS (SELECT 1 FROM symptom_logs sl
                        WHERE sl.patient_system_id = v_patient
                          AND sl.notes = 'Demo log (dev seed 47): better after rest');
END $$;
