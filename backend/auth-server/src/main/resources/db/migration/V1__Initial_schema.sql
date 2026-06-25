-- CareNexus Auth Server - Initial Schema

-- Users table
CREATE TABLE users (
    user_id SERIAL PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(20),
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_is_active ON users(is_active);

-- Refresh tokens table
CREATE TABLE refresh_tokens (
    token_id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    is_revoked BOOLEAN DEFAULT false,
    revoked_at TIMESTAMP,
    device_type VARCHAR(100),
    device_info VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_is_revoked ON refresh_tokens(is_revoked);

-- Care team relationships (doctor-patient)
CREATE TABLE care_team (
    care_team_id SERIAL PRIMARY KEY,
    patient_id INTEGER NOT NULL,
    doctor_id INTEGER,
    caregiver_id INTEGER,
    facility_id INTEGER,
    role VARCHAR(50) NOT NULL,
    assigned_at TIMESTAMP NOT NULL,
    unassigned_at TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    CONSTRAINT fk_care_team_patient FOREIGN KEY (patient_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_care_team_patient_id ON care_team(patient_id);
CREATE INDEX idx_care_team_doctor_id ON care_team(doctor_id);
CREATE INDEX idx_care_team_caregiver_id ON care_team(caregiver_id);
CREATE INDEX idx_care_team_is_active ON care_team(is_active);
CREATE INDEX idx_care_team_role ON care_team(role);

-- Dependent access (family caregiver relationships)
CREATE TABLE dependent_access (
    access_id SERIAL PRIMARY KEY,
    dependent_id INTEGER NOT NULL,
    caregiver_id INTEGER NOT NULL,
    guardian_id INTEGER,
    permission_type VARCHAR(100),
    authorized_at TIMESTAMP NOT NULL,
    authorized_by INTEGER,
    revoked_at TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    CONSTRAINT fk_dependent_access_dependent FOREIGN KEY (dependent_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_dependent_access_caregiver FOREIGN KEY (caregiver_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_dependent_access_dependent_id ON dependent_access(dependent_id);
CREATE INDEX idx_dependent_access_caregiver_id ON dependent_access(caregiver_id);
CREATE INDEX idx_dependent_access_is_active ON dependent_access(is_active);

-- Audit logs
CREATE TABLE audit_log (
    audit_id SERIAL PRIMARY KEY,
    user_id INTEGER,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100),
    resource_id VARCHAR(255),
    status VARCHAR(50),
    ip_address VARCHAR(45),
    user_agent TEXT,
    error_message TEXT,
    timestamp TIMESTAMP NOT NULL,
    CONSTRAINT fk_audit_log_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_action ON audit_log(action);
CREATE INDEX idx_audit_log_resource_type ON audit_log(resource_type);
CREATE INDEX idx_audit_log_timestamp ON audit_log(timestamp);
