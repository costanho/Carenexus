-- ============================================================
-- DEV ONLY (local/docker profiles). Lets the demo patient Thomas Dube (PAT-00021 on a fresh dev DB)
-- sign in, so the patient side of the messaging and video-call demo works. V6 inserted him with a
-- placeholder hash that matches no password.
--
-- Demo credentials (public, development only, treat as burned):
--   Email:    thomas.dube@carenexus.com
--   Password: patient@thomas
--   Role:     PATIENT
--
-- Only replaces the V6 placeholder, so a password changed later is kept. Safe when the user does
-- not exist (updates nothing). This version (V21) is taken by the dev seed: the next schema
-- migration in db/migration is V22.
-- ============================================================
UPDATE users
   SET password_hash = '$2a$10$bSi7sk0xJ9DYnM7StjzsW.TuQSTGqfKzn1bguCJD.g9zb19hpQnbu',
       updated_at    = CURRENT_TIMESTAMP
 WHERE email = 'thomas.dube@carenexus.com'
   AND password_hash = '$2b$10$hashedpasswordhere';
