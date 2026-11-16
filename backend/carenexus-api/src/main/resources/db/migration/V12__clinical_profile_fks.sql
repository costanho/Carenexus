-- ============================================================
-- V12: clinical foreign keys must point at the right profile table
--   V3 pointed every *_system_id column at users(system_id), so a CAREGIVER or PATIENT
--   system id could be stored as an appointment's doctor (and vice versa).
--   This adds FKs to patients / doctors / caregivers (system_id is UNIQUE there).
--
--   Safety on existing databases: each FK is added NOT VALID (enforced for every new or
--   updated row, existing rows are not checked), then VALIDATEd only when no existing row
--   violates it. If violators exist, a NOTICE is raised and the constraint stays NOT VALID
--   until the data is cleaned and `ALTER TABLE ... VALIDATE CONSTRAINT ...` is run.
--   The original users(system_id) FKs are kept, so delete behaviour is unchanged.
--   The dev seed data satisfies all of these.
-- ============================================================

DO $$
DECLARE
  fk RECORD;
  violators BIGINT;
BEGIN
  FOR fk IN
    SELECT * FROM (VALUES
      -- table,             column,                  ref table,    constraint name,                               on delete
      ('appointments',     'patient_system_id',     'patients',   'appointments_patient_profile_fkey',            'RESTRICT'),
      ('appointments',     'doctor_system_id',      'doctors',    'appointments_doctor_profile_fkey',             'RESTRICT'),
      ('consultations',    'patient_system_id',     'patients',   'consultations_patient_profile_fkey',           'RESTRICT'),
      ('consultations',    'doctor_system_id',      'doctors',    'consultations_doctor_profile_fkey',            'RESTRICT'),
      ('medical_records',  'patient_system_id',     'patients',   'medical_records_patient_profile_fkey',         'RESTRICT'),
      ('medical_records',  'doctor_system_id',      'doctors',    'medical_records_doctor_profile_fkey',          'RESTRICT'),
      ('prescriptions',    'patient_system_id',     'patients',   'prescriptions_patient_profile_fkey',           'RESTRICT'),
      ('prescriptions',    'doctor_system_id',      'doctors',    'prescriptions_doctor_profile_fkey',            'RESTRICT'),
      ('lab_results',      'patient_system_id',     'patients',   'lab_results_patient_profile_fkey',             'RESTRICT'),
      ('lab_results',      'doctor_system_id',      'doctors',    'lab_results_doctor_profile_fkey',              'RESTRICT'),
      ('lab_results',      'reviewed_by_system_id', 'doctors',    'lab_results_reviewed_by_profile_fkey',         'SET NULL'),
      ('imaging_results',  'patient_system_id',     'patients',   'imaging_results_patient_profile_fkey',         'RESTRICT'),
      ('imaging_results',  'doctor_system_id',      'doctors',    'imaging_results_doctor_profile_fkey',          'RESTRICT'),
      ('imaging_results',  'reviewed_by_system_id', 'doctors',    'imaging_results_reviewed_by_profile_fkey',     'SET NULL'),
      ('dependent_access', 'patient_system_id',     'patients',   'dependent_access_patient_profile_fkey',        'CASCADE'),
      ('dependent_access', 'caregiver_system_id',   'caregivers', 'dependent_access_caregiver_profile_fkey',      'CASCADE'),
      ('care_team',        'patient_system_id',     'patients',   'care_team_patient_profile_fkey',               'CASCADE')
    ) AS t(tbl, col, ref_tbl, cname, on_delete)
  LOOP
    IF to_regclass(fk.tbl) IS NULL THEN
      RAISE NOTICE 'V12: table % does not exist, skipping %', fk.tbl, fk.cname;
      CONTINUE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = fk.cname) THEN
      EXECUTE format(
        'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(system_id) ON DELETE %s NOT VALID',
        fk.tbl, fk.cname, fk.col, fk.ref_tbl, fk.on_delete);
    END IF;

    EXECUTE format(
      'SELECT count(*) FROM %I t WHERE t.%I IS NOT NULL AND NOT EXISTS (SELECT 1 FROM %I r WHERE r.system_id = t.%I)',
      fk.tbl, fk.col, fk.ref_tbl, fk.col) INTO violators;

    IF violators = 0 THEN
      EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', fk.tbl, fk.cname);
    ELSE
      RAISE NOTICE 'V12: % existing row(s) in %.% do not reference %; % left NOT VALID',
        violators, fk.tbl, fk.col, fk.ref_tbl, fk.cname;
    END IF;
  END LOOP;
END $$;
