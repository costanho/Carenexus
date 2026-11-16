-- ============================================================
-- V8: Add consultation records for DOC-00002 (James Banda)
-- ============================================================

-- ============================================================
-- SECTION 1: Past completed appointments (for COMPLETED consultations)
-- ============================================================

INSERT INTO appointments (appointment_id, patient_system_id, doctor_system_id, facility_id, scheduled_at, duration_minutes, type, status, reason_for_visit, reminder_sent, notes, created_at, updated_at) VALUES
(29, (SELECT system_id FROM users WHERE email='chipo.ncube@carenexus.com'),  (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'), 1, '2026-06-05T09:00:00', 30, 'IN_PERSON', 'COMPLETED', 'Initial health baseline assessment and registration',              TRUE,  NULL,                                                                 '2026-05-28T10:00:00', '2026-06-05T09:35:00'),
(30, (SELECT system_id FROM users WHERE email='peter.nkosi@carenexus.com'),  (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'), 1, '2026-06-12T11:00:00', 45, 'IN_PERSON', 'COMPLETED', 'COPD routine monitoring and spirometry review',                    TRUE,  NULL,                                                                 '2026-06-01T08:00:00', '2026-06-12T11:52:00'),
(31, (SELECT system_id FROM users WHERE email='thomas.dube@carenexus.com'),  (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'), 1, '2026-06-15T10:00:00', 30, 'IN_PERSON', 'COMPLETED', 'Hypertension and type 2 diabetes medication initiation review',    TRUE,  NULL,                                                                 '2026-06-08T09:00:00', '2026-06-15T10:35:00'),
(32, (SELECT system_id FROM users WHERE email='james.dube@carenexus.com'),   (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'), 1, '2026-07-03T14:00:00', 30, 'IN_PERSON', 'COMPLETED', 'Routine annual health check and preventive screening',             TRUE,  NULL,                                                                 '2026-06-25T11:00:00', '2026-07-03T14:38:00')
ON CONFLICT (appointment_id) DO NOTHING;

-- ============================================================
-- SECTION 2: Consultations for DOC-00002 (James Banda)
-- ============================================================

INSERT INTO consultations (consultation_id, appointment_id, patient_system_id, doctor_system_id, chief_complaint, history_of_present_illness, physical_examination, clinical_notes, diagnosis, treatment_plan, follow_up_instructions, follow_up_required, follow_up_date, duration_minutes, status, started_at, completed_at, created_at) VALUES

-- COMPLETED: Chipo Ncube — initial health baseline
(13, 29,
 (SELECT system_id FROM users WHERE email='chipo.ncube@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'New patient registration — general health baseline assessment. No acute complaints.',
 'Chipo Ncube is a 34-year-old female presenting for an initial registration consultation. She reports no current acute symptoms. Background history includes mild seasonal allergic rhinitis managed with antihistamines as needed. No chronic conditions, no regular medications. Non-smoker, occasional alcohol. Works as a secondary school teacher. Family history: mother has type 2 diabetes.',
 'General: Well-appearing, no acute distress. BMI 24.1 kg/m². Vitals: BP 118/76 mmHg, HR 72 bpm (regular), Temp 36.7°C, SpO2 99%. Cardiovascular: Regular rate and rhythm, no murmurs. Respiratory: Clear to auscultation bilaterally. Abdomen: Soft, non-tender. No lymphadenopathy.',
 'Healthy 34-year-old female with no current acute illness. Family history of T2DM noted — counselled on lifestyle risk reduction. Cervical smear up to date (last 2024). Baseline bloods ordered: FBC, fasting glucose, lipid panel, TFTs, Vitamin D.',
 'Healthy adult — no acute illness. Seasonal allergic rhinitis (mild, intermittent). Elevated family risk for type 2 diabetes — lifestyle counselling provided.',
 '1. Baseline bloods as above — review results at follow-up.\n2. Continue cetirizine 10mg PRN for allergic rhinitis during pollen season.\n3. Lifestyle advice: maintain current BMI, 150 min moderate exercise per week, balanced diet low in refined sugars given family T2DM risk.\n4. Cervical screening — next due 2027.',
 'Return in 4–6 weeks for baseline blood results review. Annual health check thereafter. Contact clinic if new symptoms arise.',
 FALSE, NULL, 30, 'COMPLETED', '2026-06-05T09:00:00', '2026-06-05T09:30:00', '2026-06-05T09:00:00'),

-- COMPLETED: Peter Nkosi — COPD monitoring
(14, 30,
 (SELECT system_id FROM users WHERE email='peter.nkosi@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'Routine COPD monitoring. Mild increase in breathlessness on exertion over the past 4 weeks.',
 'Peter Nkosi is a 62-year-old male with a 7-year history of COPD (GOLD Stage II — moderate). He reports a mild but progressive increase in exertional dyspnoea over the past 4 weeks, particularly when climbing stairs. No change in cough frequency or sputum production. No fever, haemoptysis, or chest pain. No recent chest infections. Adherent with Tiotropium inhaler. Ex-smoker — quit 2018 after 30 pack-year history.',
 'General: Mildly dyspnoeic at rest, barrel-chest morphology. Vitals: BP 132/82 mmHg, HR 80 bpm, RR 18/min, SpO2 94% on air (down from 96% at last visit). Respiratory: Reduced air entry bilaterally at bases, mild wheeze. Prolonged expiration. No accessory muscle use. No peripheral oedema. Cardiovascular: Regular rate and rhythm.',
 'SpO2 drop from 96% to 94% is clinically significant. Spirometry requested: FEV1/FVC likely deteriorated. Inhaler technique reviewed — adequate. No current signs of infective exacerbation. Increase in symptoms warrants step-up of bronchodilator therapy.',
 'COPD, GOLD Stage II (moderate) — mild disease progression. Increased exertional dyspnoea without acute exacerbation.',
 '1. Step-up therapy: add LABA (Salmeterol 50mcg via Accuhaler) to existing Tiotropium. Inhaler technique re-demonstrated.\n2. Refer to pulmonology for repeat spirometry and disease staging.\n3. Pulmonary rehabilitation programme referral — evidence-based for GOLD II.\n4. SpO2 home monitoring diary.\n5. Influenza and pneumococcal vaccinations up to date — confirm.',
 'Pulmonology appointment within 6 weeks. Return sooner if SpO2 drops below 92%, increased sputum purulence, or acute dyspnoea. Emergency plan discussed.',
 TRUE, '2026-07-24', 45, 'COMPLETED', '2026-06-12T11:00:00', '2026-06-12T11:45:00', '2026-06-12T11:00:00'),

-- COMPLETED: Thomas Dube — hypertension & T2DM initiation
(15, 31,
 (SELECT system_id FROM users WHERE email='thomas.dube@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'New diagnosis follow-up — hypertension and type 2 diabetes. Initiating pharmacotherapy.',
 'Thomas Dube is a 52-year-old male presenting for follow-up after recent diagnosis of hypertension (confirmed on 3 separate readings >140/90 mmHg) and type 2 diabetes (HbA1c 58 mmol/mol, fasting glucose 8.4 mmol/L). He reports no symptoms directly attributable to either condition. BMI 31.2 kg/m² (obese class I). Sedentary occupation. Low adherence to previous lifestyle advice. No chest pain, visual disturbance, or polyuria.',
 'Vitals: BP 148/92 mmHg (sitting, right arm), HR 78 bpm, BMI 31.2. Fundoscopy: No diabetic retinopathy changes. Peripheral pulses: Present bilaterally. Feet: No ulcers, sensation intact (10g monofilament). Urine dipstick: Trace protein.',
 'New-onset T2DM and stage 1 hypertension with metabolic syndrome features. Microalbuminuria requires monitoring. Starting dual pharmacotherapy. Dietitian referral essential. Cardiac risk: 10-year QRISK3 calculated at 18% — moderate-high risk.',
 'Type 2 diabetes mellitus (HbA1c 58 mmol/mol). Stage 1 hypertension. Obesity class I (BMI 31.2). Metabolic syndrome.',
 '1. Metformin 500mg BD with meals — titrate to 1g BD after 4 weeks if tolerated (GI side effects common initially).\n2. Amlodipine 5mg OD for hypertension (ACEI deferred pending repeat renal function).\n3. Atorvastatin 10mg nocte — QRISK3 >10%, statin indicated.\n4. Dietitian referral for diabetic diet and weight management programme.\n5. Blood glucose monitoring education — HbA1c target <53 mmol/mol.\n6. Repeat renal function, eGFR, and urine ACR in 3 months.',
 'Repeat bloods in 3 months: HbA1c, lipid panel, eGFR, urine ACR. BP target <130/80 mmHg. Return in 6 weeks for medication tolerance review. Diabetes education programme referral.',
 TRUE, '2026-07-27', 30, 'COMPLETED', '2026-06-15T10:00:00', '2026-06-15T10:30:00', '2026-06-15T10:00:00'),

-- COMPLETED: James Dube — annual health check
(16, 32,
 (SELECT system_id FROM users WHERE email='james.dube@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'Routine annual health check. No acute complaints. Requesting general preventive health review.',
 'James Dube is a 41-year-old male presenting for a routine annual health check. No acute symptoms. Non-smoker, moderate alcohol (12–14 units/week — above recommended safe limit). Physically active — runs 3× per week. Works in finance. No chronic conditions, no regular medications. Family history: father had MI at age 58.',
 'General: Well-appearing. BMI 26.8. Vitals: BP 126/80 mmHg, HR 64 bpm (regular), SpO2 100%. Cardiovascular: Regular rate and rhythm, no murmurs. Respiratory: Clear. Abdomen: Soft, no organomegaly. No lymphadenopathy.',
 'Healthy 41-year-old male. Alcohol intake modestly above recommended guidelines — brief intervention provided. Positive family history of premature cardiovascular disease — QRISK3 7.8% (low risk, but monitor). Baseline bloods: total cholesterol 5.1 mmol/L (borderline), LDL 3.2 mmol/L, HDL 1.4 mmol/L, fasting glucose 5.3 mmol/L (normal). BP well controlled.',
 'Healthy adult — no acute illness. Above-guideline alcohol intake (mild). Borderline lipid profile — dietary intervention first-line. Positive family history of cardiovascular disease.',
 '1. Alcohol reduction counselling — target <14 units/week, with at least 2 alcohol-free days. AUDIT-C discussed.\n2. Dietary advice: reduce saturated fat to improve LDL. Recheck lipids in 6 months.\n3. Maintain current physical activity levels.\n4. No pharmacotherapy indicated at this time.\n5. PSA discussion deferred — patient to decide after reading information leaflet.',
 'Annual health check next year. Repeat fasting lipids in 6 months. Contact clinic if chest pain, palpitations, or new symptoms develop.',
 FALSE, NULL, 30, 'COMPLETED', '2026-07-03T14:00:00', '2026-07-03T14:30:00', '2026-07-03T14:00:00'),

-- DRAFT: Chipo Ncube — first formal consultation (appt 7, scheduled 2026-07-18)
(17, 7,
 (SELECT system_id FROM users WHERE email='chipo.ncube@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'First formal consultation and results review following baseline bloods from initial registration.',
 NULL, NULL, NULL, NULL, NULL, NULL,
 FALSE, NULL, 30, 'DRAFT', NULL, NULL, '2026-07-10T09:00:00'),

-- DRAFT: Agnes Zimba — pain management review (appt 25, scheduled 2026-07-28)
(18, 25,
 (SELECT system_id FROM users WHERE email='agnes.zimba@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'Chronic pain management review. Tramadol efficacy assessment and CKD analgesic safety check.',
 NULL, NULL, NULL, NULL, NULL, NULL,
 FALSE, NULL, 30, 'DRAFT', NULL, NULL, '2026-07-15T14:00:00'),

-- DRAFT: James Dube — routine check-up (appt 23, scheduled 2026-08-03)
(19, 23,
 (SELECT system_id FROM users WHERE email='james.dube@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'Follow-up — lipid results review and alcohol reduction progress check.',
 NULL, NULL, NULL, NULL, NULL, NULL,
 FALSE, NULL, 30, 'DRAFT', NULL, NULL, '2026-07-20T10:00:00'),

-- DRAFT: Peter Nkosi — COPD follow-up (appt 12, scheduled 2026-08-10)
(20, 12,
 (SELECT system_id FROM users WHERE email='peter.nkosi@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'COPD follow-up. Review of spirometry results and response to new LABA therapy.',
 NULL, NULL, NULL, NULL, NULL, NULL,
 FALSE, NULL, 45, 'DRAFT', NULL, NULL, '2026-07-28T10:00:00'),

-- DRAFT: Thomas Dube — diabetes medication review (appt 16, scheduled 2026-08-12)
(21, 16,
 (SELECT system_id FROM users WHERE email='thomas.dube@carenexus.com'),
 (SELECT system_id FROM users WHERE email='james.banda@carenexus.com'),
 'Diabetes and hypertension medication tolerance review. Assess Metformin side effects at 6-week mark.',
 NULL, NULL, NULL, NULL, NULL, NULL,
 FALSE, NULL, 30, 'DRAFT', NULL, NULL, '2026-07-28T14:00:00')

ON CONFLICT (consultation_id) DO NOTHING;

-- ============================================================
-- Update sequences
-- ============================================================
SELECT setval('appointments_appointment_id_seq', (SELECT MAX(appointment_id) FROM appointments));
SELECT setval('consultations_consultation_id_seq', (SELECT MAX(consultation_id) FROM consultations));
