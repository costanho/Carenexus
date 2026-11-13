-- ============================================================
-- DEV ONLY (local and docker profiles): demo care plans (V46, be-care-plans).
--
--   Linda Moyo (linda.moyo@carenexus.com)   'Blood pressure management', ACTIVE, two goals, two interventions,
--                                            authored by a doctor related to her (James Banda when he is,
--                                            otherwise the first related doctor)
--   Thomas Dube (thomas.dube@carenexus.com) 'Diabetes self-management', ACTIVE, two goals, two interventions,
--                                            authored by James Banda (james.banda@carenexus.com)
--
-- Repeatable and idempotent: a plan is inserted only when the same patient has no plan with the same title from the
-- same doctor, and its goals and interventions only together with it. Each plan is skipped when its users are
-- missing. Dates are relative to the day the script runs.
-- ============================================================
DO $$
DECLARE
    v_linda   VARCHAR(20);
    v_thomas  VARCHAR(20);
    v_james   VARCHAR(20);
    v_doctor  VARCHAR(20);
    v_plan    INTEGER;
    v_now     TIMESTAMP := date_trunc('second', LOCALTIMESTAMP);
BEGIN
    SELECT u.system_id INTO v_linda
      FROM users u JOIN patients p ON p.system_id = u.system_id
     WHERE u.email = 'linda.moyo@carenexus.com' AND u.role = 'PATIENT';

    SELECT u.system_id INTO v_thomas
      FROM users u JOIN patients p ON p.system_id = u.system_id
     WHERE u.email = 'thomas.dube@carenexus.com' AND u.role = 'PATIENT';

    SELECT u.system_id INTO v_james
      FROM users u JOIN doctors d ON d.system_id = u.system_id
     WHERE u.email = 'james.banda@carenexus.com' AND u.role = 'DOCTOR';

    -- ---------- Linda Moyo ----------
    IF v_linda IS NULL THEN
        RAISE NOTICE 'dev seed 46 (care plans): demo patient Linda Moyo not found, her plan is skipped';
    ELSE
        -- A doctor with the relationship the API requires (care team, non-cancelled appointment or consultation).
        SELECT rel.doctor INTO v_doctor
          FROM (
                SELECT ct.provider_system_id AS doctor
                  FROM care_team ct
                 WHERE ct.patient_system_id = v_linda AND ct.is_active
                   AND ct.role IN ('PRIMARY_DOCTOR', 'DOCTOR', 'SPECIALIST')
                UNION
                SELECT a.doctor_system_id FROM appointments a
                 WHERE a.patient_system_id = v_linda AND a.status <> 'CANCELLED'
                UNION
                SELECT c.doctor_system_id FROM consultations c
                 WHERE c.patient_system_id = v_linda
               ) rel
          JOIN doctors d ON d.system_id = rel.doctor
         ORDER BY (rel.doctor = v_james) DESC NULLS LAST, rel.doctor
         LIMIT 1;

        IF v_doctor IS NULL THEN
            RAISE NOTICE 'dev seed 46 (care plans): no doctor related to Linda Moyo, her plan is skipped';
        ELSIF NOT EXISTS (SELECT 1 FROM care_plans
                           WHERE patient_system_id = v_linda AND doctor_system_id = v_doctor
                             AND title = 'Blood pressure management') THEN
            INSERT INTO care_plans (patient_system_id, doctor_system_id, consultation_id, title, description, status,
                                    start_date, target_date, review_date, created_at, updated_at)
            VALUES (v_linda, v_doctor, NULL, 'Blood pressure management',
                    'Six-month plan to bring home blood pressure readings into the target range.', 'ACTIVE',
                    CURRENT_DATE - 14, CURRENT_DATE + 166, CURRENT_DATE + 30,
                    v_now - INTERVAL '14 days', v_now - INTERVAL '14 days')
            RETURNING plan_id INTO v_plan;

            INSERT INTO care_plan_goals (plan_id, description, target_date, status, progress_note, position,
                                         created_at, updated_at)
            VALUES (v_plan, 'Walk 30 minutes five days a week', CURRENT_DATE + 60, 'IN_PROGRESS',
                    'Managing three days a week so far', 0, v_now - INTERVAL '14 days', v_now - INTERVAL '3 days'),
                   (v_plan, 'Record blood pressure every morning', NULL, 'OPEN', NULL, 1,
                    v_now - INTERVAL '14 days', v_now - INTERVAL '14 days');

            INSERT INTO care_plan_interventions (plan_id, description, frequency, responsible, position)
            VALUES (v_plan, 'Home blood pressure monitoring', 'Daily', 'Patient', 0),
                   (v_plan, 'Dietitian session on salt intake', 'Once', 'Dietitian', 1);
        END IF;
    END IF;

    -- ---------- Thomas Dube ----------
    IF v_thomas IS NULL OR v_james IS NULL THEN
        RAISE NOTICE 'dev seed 46 (care plans): Thomas Dube or James Banda not found, his plan is skipped';
    ELSIF NOT EXISTS (SELECT 1 FROM care_plans
                       WHERE patient_system_id = v_thomas AND doctor_system_id = v_james
                         AND title = 'Diabetes self-management') THEN
        INSERT INTO care_plans (patient_system_id, doctor_system_id, consultation_id, title, description, status,
                                start_date, target_date, review_date, created_at, updated_at)
        VALUES (v_thomas, v_james, NULL, 'Diabetes self-management',
                'Three-month plan for blood sugar control, activity and foot care.', 'ACTIVE',
                CURRENT_DATE - 7, CURRENT_DATE + 83, CURRENT_DATE + 21,
                v_now - INTERVAL '7 days', v_now - INTERVAL '7 days')
        RETURNING plan_id INTO v_plan;

        INSERT INTO care_plan_goals (plan_id, description, target_date, status, progress_note, position,
                                     created_at, updated_at)
        VALUES (v_plan, 'Check fasting blood sugar every morning', NULL, 'IN_PROGRESS', NULL, 0,
                v_now - INTERVAL '7 days', v_now - INTERVAL '2 days'),
               (v_plan, 'Lose 3 kg', CURRENT_DATE + 83, 'OPEN', NULL, 1,
                v_now - INTERVAL '7 days', v_now - INTERVAL '7 days');

        INSERT INTO care_plan_interventions (plan_id, description, frequency, responsible, position)
        VALUES (v_plan, 'Glucose meter readings logged in the app', 'Daily', 'Patient', 0),
               (v_plan, 'Daily foot check', 'Daily', 'Caregiver', 1);
    END IF;
END $$;
