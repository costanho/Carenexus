-- DEV ONLY (local/docker profiles). Demo pair for messaging and calls: Thomas Dube approves his
-- caregiver Sarah Dube for messaging and joining calls. Everything else stays "needs approval".
UPDATE dependent_access
   SET can_message_care_team = TRUE,
       can_join_calls = TRUE,
       messaging_approved_at = now(), messaging_approved_by_system_id = patient_system_id,
       calls_approved_at = now(),     calls_approved_by_system_id = patient_system_id
 WHERE caregiver_system_id = (SELECT system_id FROM users WHERE email = 'sarah.dube@carenexus.com')
   AND patient_system_id   = (SELECT system_id FROM users WHERE email = 'thomas.dube@carenexus.com');
