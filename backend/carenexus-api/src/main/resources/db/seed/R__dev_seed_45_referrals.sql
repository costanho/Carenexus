-- ============================================================
-- DEV ONLY (local and docker profiles): demo referrals for the referral screens.
--
-- Issued by the demo doctor james.banda@carenexus.com:
--   Thomas Dube -> Dr. Michael Kariuki (Cardiology)      PENDING   URGENT    platform specialist, from the
--                                                                            V8 consultation of appointment 31
--   Thomas Dube -> Dr. Elizabeth Njeri (Ophthalmology)   ACCEPTED  ROUTINE   platform specialist; she also gets
--                                                                            the active SPECIALIST care_team row
--                                                                            acceptance creates
--   Thomas Dube -> Dr. Peter Moyo, Harare Renal Unit     PENDING   ROUTINE   external specialist
--   Linda Moyo  -> (not yet chosen)                      PENDING   ROUTINE   unassigned
--
-- Repeatable and idempotent: a row is inserted only when no referral with the same patient, referring doctor
-- and reason exists. Each part is skipped when its demo users are missing. Creates no notifications.
-- ============================================================
DO $$
DECLARE
    v_banda    VARCHAR(20);
    v_kariuki  VARCHAR(20);
    v_njeri    VARCHAR(20);
    v_thomas   VARCHAR(20);
    v_linda    VARCHAR(20);
    v_consult  INTEGER;
    v_now      TIMESTAMP := date_trunc('second', LOCALTIMESTAMP);
    v_reason   TEXT;
BEGIN
    SELECT u.system_id INTO v_banda
      FROM users u JOIN doctors d ON d.system_id = u.system_id
     WHERE u.email = 'james.banda@carenexus.com' AND u.role = 'DOCTOR';

    IF v_banda IS NULL THEN
        RAISE NOTICE 'dev seed 45 (referrals): demo doctor not found, nothing inserted';
        RETURN;
    END IF;

    SELECT u.system_id INTO v_kariuki
      FROM users u JOIN doctors d ON d.system_id = u.system_id
     WHERE u.email = 'michael.kariuki@carenexus.com' AND u.role = 'DOCTOR';
    SELECT u.system_id INTO v_njeri
      FROM users u JOIN doctors d ON d.system_id = u.system_id
     WHERE u.email = 'elizabeth.njeri@carenexus.com' AND u.role = 'DOCTOR';
    SELECT u.system_id INTO v_thomas
      FROM users u JOIN patients p ON p.system_id = u.system_id
     WHERE u.email = 'thomas.dube@carenexus.com' AND u.role = 'PATIENT';
    SELECT u.system_id INTO v_linda
      FROM users u JOIN patients p ON p.system_id = u.system_id
     WHERE u.email = 'linda.moyo@carenexus.com' AND u.role = 'PATIENT';

    IF v_thomas IS NOT NULL THEN
        SELECT c.consultation_id INTO v_consult
          FROM consultations c
         WHERE c.appointment_id = 31 AND c.patient_system_id = v_thomas AND c.doctor_system_id = v_banda
         ORDER BY c.consultation_id
         LIMIT 1;

        -- PENDING, URGENT, to a platform cardiologist.
        IF v_kariuki IS NOT NULL THEN
            v_reason := 'Exertional chest tightness with poorly controlled hypertension; cardiology assessment requested.';
            INSERT INTO referrals (patient_system_id, referring_doctor_system_id, specialist_system_id, reason,
                                   clinical_summary, status, priority, consultation_id, referral_date,
                                   created_at, updated_at)
            SELECT v_thomas, v_banda, v_kariuki, v_reason,
                   'Hypertension and type 2 diabetes on treatment since June. Please assess for ischaemic heart disease.',
                   'PENDING', 'URGENT', v_consult, CURRENT_DATE - 1,
                   v_now - INTERVAL '1 day', v_now - INTERVAL '1 day'
             WHERE NOT EXISTS (SELECT 1 FROM referrals r
                                WHERE r.patient_system_id = v_thomas AND r.referring_doctor_system_id = v_banda
                                  AND r.reason = v_reason);
        END IF;

        -- ACCEPTED, ROUTINE, to a platform ophthalmologist, who then has a care relationship with the patient.
        IF v_njeri IS NOT NULL THEN
            v_reason := 'Annual diabetic eye screening.';
            INSERT INTO referrals (patient_system_id, referring_doctor_system_id, specialist_system_id, reason,
                                   clinical_summary, status, priority, referral_date, outcome_note,
                                   responded_at, created_at, updated_at)
            SELECT v_thomas, v_banda, v_njeri, v_reason,
                   'Type 2 diabetes diagnosed this year; no previous retinal examination on record.',
                   'ACCEPTED', 'ROUTINE', CURRENT_DATE - 10,
                   'Accepted. Retinal screening will be booked at the next eye clinic.',
                   v_now - INTERVAL '8 days', v_now - INTERVAL '10 days', v_now - INTERVAL '8 days'
             WHERE NOT EXISTS (SELECT 1 FROM referrals r
                                WHERE r.patient_system_id = v_thomas AND r.referring_doctor_system_id = v_banda
                                  AND r.reason = v_reason);

            INSERT INTO care_team (patient_system_id, provider_system_id, role, joined_date, is_primary, is_active,
                                   assigned_at, created_at)
            SELECT v_thomas, v_njeri, 'SPECIALIST', CURRENT_DATE - 8, FALSE, TRUE,
                   v_now - INTERVAL '8 days', v_now - INTERVAL '8 days'
             WHERE NOT EXISTS (SELECT 1 FROM care_team ct
                                WHERE ct.patient_system_id = v_thomas AND ct.provider_system_id = v_njeri
                                  AND ct.is_active)
            ON CONFLICT DO NOTHING;
        END IF;

        -- PENDING, ROUTINE, to a specialist outside the platform.
        v_reason := 'Raised creatinine on two occasions; nephrology opinion requested.';
        INSERT INTO referrals (patient_system_id, referring_doctor_system_id, external_specialist_name,
                               external_specialist_organisation, external_specialist_contact, reason,
                               status, priority, referral_date, created_at, updated_at)
        SELECT v_thomas, v_banda, 'Dr. Peter Moyo', 'Harare Renal Unit', '+263 242 700 100', v_reason,
               'PENDING', 'ROUTINE', CURRENT_DATE - 3, v_now - INTERVAL '3 days', v_now - INTERVAL '3 days'
         WHERE NOT EXISTS (SELECT 1 FROM referrals r
                            WHERE r.patient_system_id = v_thomas AND r.referring_doctor_system_id = v_banda
                              AND r.reason = v_reason);
    END IF;

    -- PENDING, ROUTINE, specialist not yet chosen.
    IF v_linda IS NOT NULL THEN
        v_reason := 'Persistent skin rash not responding to topical treatment; dermatology review requested.';
        INSERT INTO referrals (patient_system_id, referring_doctor_system_id, reason, status, priority,
                               referral_date, created_at, updated_at)
        SELECT v_linda, v_banda, v_reason, 'PENDING', 'ROUTINE', CURRENT_DATE - 2,
               v_now - INTERVAL '2 days', v_now - INTERVAL '2 days'
         WHERE NOT EXISTS (SELECT 1 FROM referrals r
                            WHERE r.patient_system_id = v_linda AND r.referring_doctor_system_id = v_banda
                              AND r.reason = v_reason);
    END IF;
END $$;
