-- ============================================================
-- DEV ONLY (local and docker profiles): medication schedule plans (be-medication, V38) for the demo patients.
--
-- Prescriptions created before V38 have no plan, so the medication-schedule sweeper would create no doses for
-- them. This adds plans for the ACTIVE prescriptions of linda.moyo@carenexus.com and of every patient with an
-- active grant to the demo caregiver sarah.dube@carenexus.com. Times follow ScheduleTimes.deriveFromFrequency;
-- as-needed prescriptions get no plan. The sweeper then creates today's remaining and tomorrow's doses.
--
-- Repeatable and idempotent (ON CONFLICT DO NOTHING: a plan edited through the API is never overwritten).
-- Skipped when the demo users are missing.
-- ============================================================
DO $$
DECLARE
    v_patient   VARCHAR(20);
    v_caregiver VARCHAR(20);
BEGIN
    SELECT system_id INTO v_patient FROM users WHERE email = 'linda.moyo@carenexus.com' AND role = 'PATIENT';
    SELECT system_id INTO v_caregiver FROM users WHERE email = 'sarah.dube@carenexus.com' AND role = 'CAREGIVER';

    IF v_patient IS NULL AND v_caregiver IS NULL THEN
        RAISE NOTICE 'dev seed 38 (medication): demo patient and demo caregiver not found, nothing inserted';
        RETURN;
    END IF;

    INSERT INTO medication_schedule_plans (prescription_id, times, updated_by_system_id, updated_at)
    SELECT rx.prescription_id, t.times, rx.doctor_system_id, date_trunc('second', LOCALTIMESTAMP)
      FROM prescriptions rx
      CROSS JOIN LATERAL (SELECT CASE
                 WHEN lower(rx.frequency) ~ '(as needed|when required|\mprn\M)' THEN NULL
                 WHEN lower(rx.frequency) ~ '(four times|4 times|\mqid\M|\mqds\M)' THEN '["08:00", "12:00", "16:00", "20:00"]'::jsonb
                 WHEN lower(rx.frequency) ~ '(three times|3 times|\mtid\M|\mtds\M)' THEN '["08:00", "14:00", "20:00"]'::jsonb
                 WHEN lower(rx.frequency) ~ '(twice|2 times|\mbid\M|\mbd\M)' THEN '["08:00", "20:00"]'::jsonb
                 WHEN lower(rx.frequency) ~ '(night|bedtime|evening)' THEN '["20:00"]'::jsonb
                 WHEN lower(rx.frequency) ~ '(once|daily|every day|morning)' THEN '["08:00"]'::jsonb
             END AS times) t
     WHERE rx.status = 'ACTIVE'
       AND t.times IS NOT NULL
       AND (rx.patient_system_id = v_patient
            OR rx.patient_system_id IN (SELECT da.patient_system_id FROM dependent_access da
                                         WHERE da.caregiver_system_id = v_caregiver AND da.is_active))
    ON CONFLICT (prescription_id) DO NOTHING;
END $$;
