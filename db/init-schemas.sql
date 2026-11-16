-- ============================================================
-- CareNexus database schema (PostgreSQL) - GENERATED REFERENCE ONLY
--
-- The source of truth is the Flyway migrations in
--   backend/carenexus-api/src/main/resources/db/migration
-- This file is a `pg_dump --schema-only` of a database migrated with
-- V1, V3, V9, V10, V11, V12, V14 (schema migrations only; no seed data).
-- Scheduling's V13 was not present when this was generated: regenerate after
-- adding or changing any migration.
--
-- DO NOT bootstrap a database from this file. Flyway would then baseline the
-- non-empty schema and V1 would fail ("relation users already exists").
-- Let the application (Flyway) create the schema instead.
--
-- Regenerate:
--   createdb carenexus_schema_ref
--   for f in $(ls backend/carenexus-api/src/main/resources/db/migration/V*.sql | sort -V); do
--     psql -v ON_ERROR_STOP=1 -1 -d carenexus_schema_ref -f "$f"; done
--   pg_dump --schema-only --no-owner --no-privileges carenexus_schema_ref > db/init-schemas.sql
--   (then restore this header)
-- ============================================================

--
-- PostgreSQL database dump
--



SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'SQL_ASCII';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: update_updated_at_column(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ai_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_alerts (
    alert_id integer NOT NULL,
    detection_id integer,
    alert_type character varying(30) NOT NULL,
    message text NOT NULL,
    priority character varying(20) DEFAULT 'MEDIUM'::character varying NOT NULL,
    action_required boolean DEFAULT false NOT NULL,
    suggested_action text,
    is_acknowledged boolean DEFAULT false NOT NULL,
    acknowledged_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20),
    acknowledged_by_system_id character varying(20),
    CONSTRAINT ai_alerts_alert_type_check CHECK (((alert_type)::text = ANY ((ARRAY['CRITICAL_VITALS'::character varying, 'DISEASE_RISK'::character varying, 'MEDICATION_INTERACTION'::character varying, 'MISSED_MEDICATION'::character varying, 'FOLLOW_UP_DUE'::character varying, 'ANOMALY_DETECTED'::character varying])::text[]))),
    CONSTRAINT ai_alerts_priority_check CHECK (((priority)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))
);


--
-- Name: TABLE ai_alerts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.ai_alerts IS 'AI clinical alerts';


--
-- Name: ai_alerts_alert_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ai_alerts_alert_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ai_alerts_alert_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ai_alerts_alert_id_seq OWNED BY public.ai_alerts.alert_id;


--
-- Name: appointments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.appointments (
    appointment_id integer NOT NULL,
    facility_id integer,
    scheduled_at timestamp without time zone NOT NULL,
    duration_minutes integer DEFAULT 30 NOT NULL,
    type character varying(20) DEFAULT 'IN_PERSON'::character varying NOT NULL,
    status character varying(20) DEFAULT 'SCHEDULED'::character varying NOT NULL,
    reason_for_visit text,
    video_consultation_link character varying(500),
    reminder_sent boolean DEFAULT false NOT NULL,
    notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20) NOT NULL,
    CONSTRAINT appointments_status_check CHECK (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying, 'NO_SHOW'::character varying, 'RESCHEDULED'::character varying])::text[]))),
    CONSTRAINT appointments_type_check CHECK (((type)::text = ANY ((ARRAY['VIDEO'::character varying, 'IN_PERSON'::character varying, 'PHONE'::character varying])::text[])))
);


--
-- Name: TABLE appointments; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.appointments IS 'Appointment bookings between patients and doctors';


--
-- Name: appointments_appointment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.appointments_appointment_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: appointments_appointment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.appointments_appointment_id_seq OWNED BY public.appointments.appointment_id;


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    audit_id bigint NOT NULL,
    action character varying(50) NOT NULL,
    resource_type character varying(50),
    resource_id integer,
    status character varying(20) NOT NULL,
    ip_address character varying(45),
    user_agent character varying(500),
    error_message character varying(500),
    "timestamp" timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_id character varying(20),
    patient_system_id character varying(20),
    actor_role character varying(20),
    http_method character varying(10),
    request_path character varying(255)
);


--
-- Name: TABLE audit_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.audit_log IS 'Audit trail of system actions';


--
-- Name: audit_log_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_log_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_log_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_log_audit_id_seq OWNED BY public.audit_log.audit_id;


--
-- Name: care_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.care_plans (
    plan_id integer NOT NULL,
    consultation_id integer,
    title character varying(255) NOT NULL,
    description text,
    goals text,
    interventions text,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    start_date date NOT NULL,
    target_date date,
    review_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20) NOT NULL,
    CONSTRAINT care_plans_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'COMPLETED'::character varying, 'ON_HOLD'::character varying, 'DISCONTINUED'::character varying])::text[])))
);


--
-- Name: TABLE care_plans; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.care_plans IS 'Patient care plans created by doctors';


--
-- Name: care_plans_plan_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.care_plans_plan_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: care_plans_plan_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.care_plans_plan_id_seq OWNED BY public.care_plans.plan_id;


--
-- Name: care_team; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.care_team (
    care_team_id integer NOT NULL,
    facility_id integer,
    role character varying(50) NOT NULL,
    joined_date date DEFAULT CURRENT_DATE NOT NULL,
    is_primary boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    provider_system_id character varying(20) NOT NULL,
    assigned_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    unassigned_at timestamp without time zone,
    CONSTRAINT care_team_role_check CHECK (((role)::text = ANY ((ARRAY['PRIMARY_DOCTOR'::character varying, 'DOCTOR'::character varying, 'SPECIALIST'::character varying, 'NURSE'::character varying, 'LAB'::character varying, 'PHARMACY'::character varying, 'THERAPIST'::character varying, 'CAREGIVER'::character varying, 'ASSIGNED_CAREGIVER'::character varying])::text[])))
);


--
-- Name: TABLE care_team; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.care_team IS 'Multi-disciplinary care team assignments';


--
-- Name: care_team_team_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.care_team_team_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: care_team_team_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.care_team_team_id_seq OWNED BY public.care_team.care_team_id;


--
-- Name: caregiver_activity_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.caregiver_activity_log (
    log_id integer NOT NULL,
    caregiver_id integer NOT NULL,
    patient_id integer NOT NULL,
    action character varying(20) NOT NULL,
    resource_type character varying(80),
    resource_id integer,
    ip_address character varying(45),
    device_info character varying(255),
    "timestamp" timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT caregiver_activity_log_action_check CHECK (((action)::text = ANY ((ARRAY['VIEW'::character varying, 'SCHEDULE'::character varying, 'MESSAGE'::character varying, 'DOWNLOAD'::character varying, 'JOIN_CONSULT'::character varying, 'UPLOAD'::character varying, 'EDIT'::character varying])::text[])))
);


--
-- Name: TABLE caregiver_activity_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.caregiver_activity_log IS 'Audit trail of caregiver actions';


--
-- Name: caregiver_activity_log_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.caregiver_activity_log_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: caregiver_activity_log_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.caregiver_activity_log_log_id_seq OWNED BY public.caregiver_activity_log.log_id;


--
-- Name: caregiver_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.caregiver_alerts (
    alert_id integer NOT NULL,
    caregiver_id integer NOT NULL,
    patient_id integer NOT NULL,
    alert_type character varying(20) NOT NULL,
    message text NOT NULL,
    severity character varying(20) DEFAULT 'MEDIUM'::character varying NOT NULL,
    action_url character varying(500),
    is_read boolean DEFAULT false NOT NULL,
    read_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT caregiver_alerts_alert_type_check CHECK (((alert_type)::text = ANY ((ARRAY['MEDICATION'::character varying, 'APPOINTMENT'::character varying, 'LAB_RESULT'::character varying, 'EMERGENCY'::character varying, 'VITAL_SIGN'::character varying, 'CARE_PLAN'::character varying])::text[]))),
    CONSTRAINT caregiver_alerts_severity_check CHECK (((severity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))
);


--
-- Name: TABLE caregiver_alerts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.caregiver_alerts IS 'Alerts for caregivers';


--
-- Name: caregiver_alerts_alert_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.caregiver_alerts_alert_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: caregiver_alerts_alert_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.caregiver_alerts_alert_id_seq OWNED BY public.caregiver_alerts.alert_id;


--
-- Name: caregivers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.caregivers (
    caregiver_id integer NOT NULL,
    relationship character varying(80),
    phone character varying(20),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_id character varying(20) NOT NULL
);


--
-- Name: TABLE caregivers; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.caregivers IS 'Caregiver profiles';


--
-- Name: caregivers_caregiver_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.caregivers_caregiver_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: caregivers_caregiver_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.caregivers_caregiver_id_seq OWNED BY public.caregivers.caregiver_id;


--
-- Name: consent_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consent_records (
    consent_id integer NOT NULL,
    patient_id integer NOT NULL,
    caregiver_id integer,
    doctor_id integer,
    consent_type character varying(20) NOT NULL,
    terms_agreed boolean DEFAULT false NOT NULL,
    scope jsonb,
    ip_address character varying(45),
    signed_date timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    received_at timestamp without time zone,
    expires_at timestamp without time zone,
    is_active boolean DEFAULT true NOT NULL,
    revoked_at timestamp without time zone,
    CONSTRAINT consent_records_consent_type_check CHECK (((consent_type)::text = ANY ((ARRAY['DATA_ACCESS'::character varying, 'CONSULTATION'::character varying, 'TREATMENT'::character varying, 'FULL_PROXY'::character varying, 'RESEARCH'::character varying, 'MARKETING'::character varying])::text[])))
);


--
-- Name: TABLE consent_records; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.consent_records IS 'Patient consent records';


--
-- Name: consent_records_consent_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.consent_records_consent_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: consent_records_consent_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.consent_records_consent_id_seq OWNED BY public.consent_records.consent_id;


--
-- Name: consultations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consultations (
    consultation_id integer NOT NULL,
    appointment_id integer NOT NULL,
    chief_complaint text,
    history_of_present_illness text,
    physical_examination text,
    clinical_notes text,
    diagnosis text,
    treatment_plan text,
    follow_up_instructions text,
    follow_up_required boolean DEFAULT false NOT NULL,
    follow_up_date date,
    duration_minutes integer,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20) NOT NULL,
    CONSTRAINT consultations_status_check CHECK (((status)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'DRAFT'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: TABLE consultations; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.consultations IS 'Clinical consultation records';


--
-- Name: consultations_consultation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.consultations_consultation_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: consultations_consultation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.consultations_consultation_id_seq OWNED BY public.consultations.consultation_id;


--
-- Name: dependent_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dependent_access (
    access_id integer NOT NULL,
    relationship character varying(80) NOT NULL,
    access_level character varying(20) DEFAULT 'VIEW_ONLY'::character varying NOT NULL,
    can_view_records boolean DEFAULT false NOT NULL,
    can_view_appointments boolean DEFAULT false NOT NULL,
    can_view_medications boolean DEFAULT false NOT NULL,
    can_view_lab_results boolean DEFAULT false NOT NULL,
    can_view_imaging boolean DEFAULT false NOT NULL,
    can_message_care_team boolean DEFAULT false NOT NULL,
    can_view_consultations boolean DEFAULT false NOT NULL,
    can_schedule_appointments boolean DEFAULT false NOT NULL,
    can_manage_care_plan boolean DEFAULT false NOT NULL,
    authorized_date timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp without time zone,
    is_active boolean DEFAULT true NOT NULL,
    revoked_at timestamp without time zone,
    caregiver_system_id character varying(20) NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    authorized_by_system_id character varying(20),
    CONSTRAINT dependent_access_access_level_check CHECK (((access_level)::text = ANY ((ARRAY['FULL_ACCESS'::character varying, 'VIEW_ONLY'::character varying, 'EDIT_ONLY'::character varying, 'CUSTOM'::character varying])::text[])))
);


--
-- Name: TABLE dependent_access; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.dependent_access IS 'Caregiver access control';


--
-- Name: dependent_access_access_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.dependent_access_access_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dependent_access_access_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.dependent_access_access_id_seq OWNED BY public.dependent_access.access_id;


--
-- Name: disease_detection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.disease_detection (
    detection_id integer NOT NULL,
    symptom_id integer,
    model_version character varying(50) NOT NULL,
    detected_conditions jsonb NOT NULL,
    confidence_score numeric(4,3),
    risk_level character varying(20) DEFAULT 'LOW'::character varying NOT NULL,
    input_data jsonb,
    recommendations jsonb,
    is_confirmed boolean DEFAULT false NOT NULL,
    confirmed_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20),
    reviewed_by_system_id character varying(20),
    CONSTRAINT disease_detection_risk_level_check CHECK (((risk_level)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))
);


--
-- Name: TABLE disease_detection; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.disease_detection IS 'AI disease detection results';


--
-- Name: disease_detection_detection_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.disease_detection_detection_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: disease_detection_detection_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.disease_detection_detection_id_seq OWNED BY public.disease_detection.detection_id;


--
-- Name: doctors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.doctors (
    doctor_id integer NOT NULL,
    specialization character varying(150),
    license_no character varying(50),
    bio text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_id character varying(20) NOT NULL
);


--
-- Name: TABLE doctors; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.doctors IS 'Doctor / Physician profiles';


--
-- Name: doctors_doctor_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.doctors_doctor_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: doctors_doctor_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.doctors_doctor_id_seq OWNED BY public.doctors.doctor_id;


--
-- Name: documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.documents (
    document_id integer NOT NULL,
    document_type character varying(20) NOT NULL,
    title character varying(300) NOT NULL,
    description text,
    file_url character varying(500) NOT NULL,
    mime_type character varying(100),
    file_size_kb integer,
    is_deleted boolean DEFAULT false NOT NULL,
    uploaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    uploaded_by_system_id character varying(20) NOT NULL,
    CONSTRAINT documents_document_type_check CHECK (((document_type)::text = ANY ((ARRAY['LAB_REPORT'::character varying, 'IMAGING'::character varying, 'PRESCRIPTION'::character varying, 'REFERRAL'::character varying, 'CONSENT'::character varying, 'DISCHARGE'::character varying, 'INSURANCE'::character varying, 'OTHER'::character varying])::text[])))
);


--
-- Name: TABLE documents; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.documents IS 'Patient document storage';


--
-- Name: documents_document_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.documents_document_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: documents_document_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.documents_document_id_seq OWNED BY public.documents.document_id;


--
-- Name: emergency_contacts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.emergency_contacts (
    contact_id integer NOT NULL,
    name character varying(150) NOT NULL,
    relationship character varying(80),
    phone character varying(20) NOT NULL,
    email character varying(150),
    is_primary boolean DEFAULT false NOT NULL,
    is_caregiver boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL
);


--
-- Name: TABLE emergency_contacts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.emergency_contacts IS 'Emergency contacts for patients';


--
-- Name: emergency_contacts_contact_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.emergency_contacts_contact_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: emergency_contacts_contact_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.emergency_contacts_contact_id_seq OWNED BY public.emergency_contacts.contact_id;


--
-- Name: facilities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.facilities (
    facility_id integer NOT NULL,
    name character varying(200) NOT NULL,
    type character varying(80),
    address character varying(300),
    phone character varying(20),
    email character varying(150),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE facilities; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.facilities IS 'Healthcare facilities and clinics';


--
-- Name: facilities_facility_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.facilities_facility_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: facilities_facility_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.facilities_facility_id_seq OWNED BY public.facilities.facility_id;


--
-- Name: health_metrics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.health_metrics (
    metric_id integer NOT NULL,
    patient_id integer NOT NULL,
    type character varying(30) NOT NULL,
    value numeric(10,3) NOT NULL,
    unit character varying(30) NOT NULL,
    source character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    device_id character varying(100),
    is_abnormal boolean DEFAULT false NOT NULL,
    notes text,
    recorded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT health_metrics_source_check CHECK (((source)::text = ANY ((ARRAY['MANUAL'::character varying, 'WEARABLE'::character varying, 'DEVICE'::character varying, 'INTEGRATION'::character varying])::text[]))),
    CONSTRAINT health_metrics_type_check CHECK (((type)::text = ANY ((ARRAY['BP_SYSTOLIC'::character varying, 'BP_DIASTOLIC'::character varying, 'HEART_RATE'::character varying, 'GLUCOSE'::character varying, 'WEIGHT'::character varying, 'HEIGHT'::character varying, 'BMI'::character varying, 'SPO2'::character varying, 'TEMPERATURE'::character varying, 'RESPIRATORY_RATE'::character varying, 'STEPS'::character varying])::text[])))
);


--
-- Name: TABLE health_metrics; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.health_metrics IS 'Patient vital signs and health metrics';


--
-- Name: health_metrics_metric_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.health_metrics_metric_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: health_metrics_metric_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.health_metrics_metric_id_seq OWNED BY public.health_metrics.metric_id;


--
-- Name: imaging_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.imaging_results (
    imaging_id integer NOT NULL,
    consultation_id integer,
    imaging_type character varying(20) NOT NULL,
    body_part text,
    radiologist_report text,
    findings text,
    impression text,
    file_url character varying(500),
    thumbnail_url character varying(500),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    image_date date NOT NULL,
    reviewed_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20) NOT NULL,
    reviewed_by_system_id character varying(20),
    CONSTRAINT imaging_results_imaging_type_check CHECK (((imaging_type)::text = ANY ((ARRAY['XRAY'::character varying, 'MRI'::character varying, 'CT_SCAN'::character varying, 'ULTRASOUND'::character varying, 'PET'::character varying, 'MAMMOGRAPHY'::character varying, 'DEXA'::character varying, 'ECHO'::character varying])::text[]))),
    CONSTRAINT imaging_results_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'REQUIRES_REVIEW'::character varying, 'CRITICAL'::character varying])::text[])))
);


--
-- Name: TABLE imaging_results; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.imaging_results IS 'Medical imaging results and reports';


--
-- Name: imaging_results_imaging_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.imaging_results_imaging_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: imaging_results_imaging_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.imaging_results_imaging_id_seq OWNED BY public.imaging_results.imaging_id;


--
-- Name: insurance_claims; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.insurance_claims (
    claim_id integer NOT NULL,
    patient_id integer NOT NULL,
    payment_id integer,
    invoice_id integer,
    insurance_provider character varying(150) NOT NULL,
    policy_number character varying(100) NOT NULL,
    member_number character varying(100),
    claim_amount numeric(12,2) NOT NULL,
    approved_amount numeric(12,2),
    rejection_reason text,
    status character varying(20) DEFAULT 'SUBMITTED'::character varying NOT NULL,
    submitted_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    resolved_at timestamp without time zone,
    documents jsonb,
    CONSTRAINT insurance_claims_status_check CHECK (((status)::text = ANY ((ARRAY['SUBMITTED'::character varying, 'PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'PARTIAL'::character varying, 'APPEALED'::character varying])::text[])))
);


--
-- Name: TABLE insurance_claims; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.insurance_claims IS 'Insurance claims';


--
-- Name: insurance_claims_claim_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.insurance_claims_claim_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: insurance_claims_claim_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.insurance_claims_claim_id_seq OWNED BY public.insurance_claims.claim_id;


--
-- Name: invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invoices (
    invoice_id integer NOT NULL,
    patient_id integer NOT NULL,
    appointment_id integer,
    consultation_id integer,
    invoice_no character varying(50) NOT NULL,
    line_items jsonb,
    subtotal numeric(12,2) DEFAULT 0.00 NOT NULL,
    tax numeric(12,2) DEFAULT 0.00 NOT NULL,
    discount numeric(12,2) DEFAULT 0.00 NOT NULL,
    total numeric(12,2) DEFAULT 0.00 NOT NULL,
    currency character varying(3) DEFAULT 'KES'::character varying NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    due_date date,
    paid_at timestamp without time zone,
    notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT invoices_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SENT'::character varying, 'PAID'::character varying, 'OVERDUE'::character varying, 'CANCELLED'::character varying, 'REFUNDED'::character varying])::text[])))
);


--
-- Name: TABLE invoices; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.invoices IS 'Patient invoices';


--
-- Name: invoices_invoice_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.invoices_invoice_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: invoices_invoice_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.invoices_invoice_id_seq OWNED BY public.invoices.invoice_id;


--
-- Name: lab_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.lab_results (
    result_id integer NOT NULL,
    consultation_id integer,
    test_name character varying(200) NOT NULL,
    test_type character varying(20) NOT NULL,
    result_value character varying(500),
    unit character varying(50),
    reference_range character varying(100),
    result_data jsonb,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    is_critical boolean DEFAULT false NOT NULL,
    test_date date NOT NULL,
    reviewed_at timestamp without time zone,
    file_url character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20) NOT NULL,
    reviewed_by_system_id character varying(20),
    CONSTRAINT lab_results_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'CRITICAL'::character varying, 'REQUIRES_REVIEW'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT lab_results_test_type_check CHECK (((test_type)::text = ANY ((ARRAY['BLOOD'::character varying, 'URINE'::character varying, 'STOOL'::character varying, 'CULTURE'::character varying, 'BIOPSY'::character varying, 'GENETIC'::character varying, 'SWAB'::character varying, 'OTHER'::character varying])::text[])))
);


--
-- Name: TABLE lab_results; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.lab_results IS 'Laboratory test results';


--
-- Name: lab_results_result_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.lab_results_result_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: lab_results_result_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.lab_results_result_id_seq OWNED BY public.lab_results.result_id;


--
-- Name: medical_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.medical_records (
    record_id integer NOT NULL,
    consultation_id integer,
    record_type character varying(20) NOT NULL,
    title text NOT NULL,
    description text,
    treatment_plan text,
    observations text,
    icd_codes jsonb,
    is_deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20) NOT NULL,
    CONSTRAINT medical_records_record_type_check CHECK (((record_type)::text = ANY ((ARRAY['CONSULT'::character varying, 'LAB'::character varying, 'IMAGING'::character varying, 'PRESCRIPTION'::character varying, 'REFERRAL'::character varying, 'DISCHARGE'::character varying, 'NOTE'::character varying, 'OPERATION'::character varying])::text[])))
);


--
-- Name: TABLE medical_records; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.medical_records IS 'Master medical records registry';


--
-- Name: medical_records_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.medical_records_record_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: medical_records_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.medical_records_record_id_seq OWNED BY public.medical_records.record_id;


--
-- Name: medication_schedules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.medication_schedules (
    schedule_id integer NOT NULL,
    prescription_id integer NOT NULL,
    patient_id integer NOT NULL,
    scheduled_time time without time zone NOT NULL,
    medication_name character varying(200) NOT NULL,
    dosage character varying(100) NOT NULL,
    is_taken boolean DEFAULT false NOT NULL,
    taken_at timestamp without time zone,
    missed boolean DEFAULT false NOT NULL,
    notes text,
    schedule_date date NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE medication_schedules; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.medication_schedules IS 'Daily medication schedule tracker';


--
-- Name: medication_schedules_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.medication_schedules_schedule_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: medication_schedules_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.medication_schedules_schedule_id_seq OWNED BY public.medication_schedules.schedule_id;


--
-- Name: message_threads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_threads (
    thread_id integer NOT NULL,
    subject character varying(300),
    thread_type character varying(30) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_message_at timestamp without time zone,
    is_archived boolean DEFAULT false NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    CONSTRAINT message_threads_thread_type_check CHECK (((thread_type)::text = ANY ((ARRAY['PATIENT_DOCTOR'::character varying, 'CAREGIVER_DOCTOR'::character varying, 'CARE_TEAM'::character varying, 'PATIENT_SUPPORT'::character varying])::text[])))
);


--
-- Name: TABLE message_threads; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.message_threads IS 'Messaging threads';


--
-- Name: message_threads_thread_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.message_threads_thread_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: message_threads_thread_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.message_threads_thread_id_seq OWNED BY public.message_threads.thread_id;


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.messages (
    message_id integer NOT NULL,
    thread_id integer NOT NULL,
    subject character varying(300),
    body text NOT NULL,
    is_read boolean DEFAULT false NOT NULL,
    read_at timestamp without time zone,
    is_urgent boolean DEFAULT false NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamp without time zone,
    sent_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    sender_system_id character varying(20) NOT NULL,
    recipient_system_id character varying(20) NOT NULL,
    patient_system_id character varying(20) NOT NULL
);


--
-- Name: TABLE messages; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.messages IS 'Secure messages';


--
-- Name: messages_message_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.messages_message_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: messages_message_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.messages_message_id_seq OWNED BY public.messages.message_id;


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    notification_id integer NOT NULL,
    type character varying(20) NOT NULL,
    title character varying(255) NOT NULL,
    message text NOT NULL,
    is_read boolean DEFAULT false NOT NULL,
    read_at timestamp without time zone,
    action_url character varying(500),
    icon character varying(50),
    priority character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    expires_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_id character varying(20) NOT NULL,
    CONSTRAINT notifications_priority_check CHECK (((priority)::text = ANY ((ARRAY['LOW'::character varying, 'NORMAL'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[]))),
    CONSTRAINT notifications_type_check CHECK (((type)::text = ANY ((ARRAY['APPOINTMENT'::character varying, 'MEDICATION'::character varying, 'LAB_RESULT'::character varying, 'MESSAGE'::character varying, 'AI_ALERT'::character varying, 'SYSTEM'::character varying, 'PAYMENT'::character varying, 'REFERRAL'::character varying])::text[])))
);


--
-- Name: TABLE notifications; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.notifications IS 'In-app notifications';


--
-- Name: notifications_notification_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.notifications_notification_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: notifications_notification_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.notifications_notification_id_seq OWNED BY public.notifications.notification_id;


--
-- Name: patients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.patients (
    patient_id integer NOT NULL,
    date_of_birth date,
    gender character varying(10),
    blood_type character varying(5),
    allergies jsonb,
    chronic_conditions jsonb,
    health_status character varying(20) DEFAULT 'STABLE'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_id character varying(20) NOT NULL,
    CONSTRAINT patients_gender_check CHECK (((gender)::text = ANY ((ARRAY['MALE'::character varying, 'FEMALE'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT patients_health_status_check CHECK (((health_status)::text = ANY ((ARRAY['STABLE'::character varying, 'MONITOR'::character varying, 'CRITICAL'::character varying])::text[])))
);


--
-- Name: TABLE patients; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.patients IS 'Patient profiles and health metadata';


--
-- Name: patients_patient_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.patients_patient_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: patients_patient_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.patients_patient_id_seq OWNED BY public.patients.patient_id;


--
-- Name: payment_methods; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_methods (
    method_id integer NOT NULL,
    user_id integer NOT NULL,
    type character varying(20) NOT NULL,
    provider character varying(100),
    last_four character varying(4),
    token character varying(255),
    is_default boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT payment_methods_type_check CHECK (((type)::text = ANY ((ARRAY['CARD'::character varying, 'INSURANCE'::character varying, 'CASH'::character varying, 'MPESA'::character varying, 'BANK_TRANSFER'::character varying, 'CRYPTO'::character varying])::text[])))
);


--
-- Name: TABLE payment_methods; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.payment_methods IS 'Patient payment methods';


--
-- Name: payment_methods_method_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.payment_methods_method_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: payment_methods_method_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.payment_methods_method_id_seq OWNED BY public.payment_methods.method_id;


--
-- Name: payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payments (
    payment_id integer NOT NULL,
    patient_id integer NOT NULL,
    appointment_id integer,
    invoice_id integer,
    payment_method_id integer,
    amount numeric(12,2) NOT NULL,
    currency character varying(3) DEFAULT 'KES'::character varying NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    transaction_id character varying(255),
    gateway character varying(50),
    gateway_response jsonb,
    paid_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT payments_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'REFUNDED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: TABLE payments; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.payments IS 'Payment transactions';


--
-- Name: payments_payment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.payments_payment_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: payments_payment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.payments_payment_id_seq OWNED BY public.payments.payment_id;


--
-- Name: permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.permissions (
    permission_id integer NOT NULL,
    caregiver_id integer NOT NULL,
    patient_id integer NOT NULL,
    resource character varying(80) NOT NULL,
    action character varying(20) NOT NULL,
    granted_by integer NOT NULL,
    granted_date timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp without time zone,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT permissions_action_check CHECK (((action)::text = ANY ((ARRAY['READ'::character varying, 'WRITE'::character varying, 'DELETE'::character varying, 'APPROVE'::character varying, 'DOWNLOAD'::character varying])::text[])))
);


--
-- Name: TABLE permissions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.permissions IS 'Granular resource-level permissions';


--
-- Name: permissions_permission_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.permissions_permission_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: permissions_permission_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.permissions_permission_id_seq OWNED BY public.permissions.permission_id;


--
-- Name: prescriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.prescriptions (
    prescription_id integer NOT NULL,
    consultation_id integer,
    medication_name character varying(200) NOT NULL,
    generic_name character varying(200),
    dosage character varying(100) NOT NULL,
    frequency character varying(100) NOT NULL,
    route character varying(20) DEFAULT 'ORAL'::character varying NOT NULL,
    quantity character varying(50),
    refills_allowed integer DEFAULT 0 NOT NULL,
    special_instructions text,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    prescribed_date date NOT NULL,
    expiry_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    doctor_system_id character varying(20) NOT NULL,
    CONSTRAINT prescriptions_route_check CHECK (((route)::text = ANY ((ARRAY['ORAL'::character varying, 'TOPICAL'::character varying, 'INJECTION'::character varying, 'INHALED'::character varying, 'SUBLINGUAL'::character varying, 'RECTAL'::character varying, 'IV'::character varying])::text[]))),
    CONSTRAINT prescriptions_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying, 'ON_HOLD'::character varying])::text[])))
);


--
-- Name: TABLE prescriptions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.prescriptions IS 'Medication prescriptions';


--
-- Name: prescriptions_prescription_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.prescriptions_prescription_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: prescriptions_prescription_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.prescriptions_prescription_id_seq OWNED BY public.prescriptions.prescription_id;


--
-- Name: referrals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.referrals (
    referral_id integer NOT NULL,
    clinical_summary text,
    reason text NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    priority character varying(20) DEFAULT 'ROUTINE'::character varying NOT NULL,
    referral_date date NOT NULL,
    appointment_date date,
    notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    patient_system_id character varying(20) NOT NULL,
    referring_doctor_system_id character varying(20) NOT NULL,
    specialist_system_id character varying(20),
    CONSTRAINT referrals_priority_check CHECK (((priority)::text = ANY ((ARRAY['ROUTINE'::character varying, 'URGENT'::character varying, 'EMERGENCY'::character varying])::text[]))),
    CONSTRAINT referrals_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACCEPTED'::character varying, 'COMPLETED'::character varying, 'DECLINED'::character varying])::text[])))
);


--
-- Name: TABLE referrals; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.referrals IS 'Patient referrals between doctors and specialists';


--
-- Name: referrals_referral_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.referrals_referral_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: referrals_referral_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.referrals_referral_id_seq OWNED BY public.referrals.referral_id;


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    token_id integer NOT NULL,
    token_hash character varying(255) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    is_revoked boolean DEFAULT false NOT NULL,
    revoked_at timestamp without time zone,
    device_type character varying(50),
    device_info character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_id character varying(20) NOT NULL
);


--
-- Name: TABLE refresh_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.refresh_tokens IS 'JWT refresh token store';


--
-- Name: refresh_tokens_token_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.refresh_tokens_token_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: refresh_tokens_token_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.refresh_tokens_token_id_seq OWNED BY public.refresh_tokens.token_id;


--
-- Name: reminders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reminders (
    reminder_id integer NOT NULL,
    patient_id integer NOT NULL,
    type character varying(20) NOT NULL,
    title character varying(255) NOT NULL,
    message text NOT NULL,
    reminder_time time without time zone NOT NULL,
    frequency character varying(20) DEFAULT 'DAILY'::character varying NOT NULL,
    start_date date NOT NULL,
    end_date date,
    is_active boolean DEFAULT true NOT NULL,
    last_sent_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT reminders_frequency_check CHECK (((frequency)::text = ANY ((ARRAY['ONCE'::character varying, 'DAILY'::character varying, 'WEEKLY'::character varying, 'MONTHLY'::character varying, 'CUSTOM'::character varying])::text[]))),
    CONSTRAINT reminders_type_check CHECK (((type)::text = ANY ((ARRAY['MEDICATION'::character varying, 'APPOINTMENT'::character varying, 'LAB_FOLLOWUP'::character varying, 'CARE_PLAN'::character varying, 'CHECK_IN'::character varying, 'VITAL_SIGN'::character varying])::text[])))
);


--
-- Name: TABLE reminders; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.reminders IS 'Automated reminders';


--
-- Name: reminders_reminder_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.reminders_reminder_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: reminders_reminder_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.reminders_reminder_id_seq OWNED BY public.reminders.reminder_id;


--
-- Name: symptom_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.symptom_logs (
    symptom_id integer NOT NULL,
    patient_id integer NOT NULL,
    symptoms jsonb NOT NULL,
    severity character varying(20) DEFAULT 'MILD'::character varying NOT NULL,
    duration_days integer,
    notes text,
    source character varying(30) DEFAULT 'PATIENT_REPORTED'::character varying NOT NULL,
    logged_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT symptom_logs_severity_check CHECK (((severity)::text = ANY ((ARRAY['MILD'::character varying, 'MODERATE'::character varying, 'SEVERE'::character varying, 'CRITICAL'::character varying])::text[]))),
    CONSTRAINT symptom_logs_source_check CHECK (((source)::text = ANY ((ARRAY['PATIENT_REPORTED'::character varying, 'DOCTOR_ENTERED'::character varying, 'WEARABLE'::character varying, 'INTEGRATION'::character varying])::text[])))
);


--
-- Name: TABLE symptom_logs; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.symptom_logs IS 'Patient-reported symptoms';


--
-- Name: symptom_logs_symptom_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.symptom_logs_symptom_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: symptom_logs_symptom_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.symptom_logs_symptom_id_seq OWNED BY public.symptom_logs.symptom_id;


--
-- Name: user_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_settings (
    setting_id integer NOT NULL,
    system_id character varying(20) NOT NULL,
    email_notifications boolean DEFAULT true NOT NULL,
    sms_notifications boolean DEFAULT true NOT NULL,
    push_notifications boolean DEFAULT true NOT NULL,
    appointment_reminders boolean DEFAULT true NOT NULL,
    prescription_reminders boolean DEFAULT true NOT NULL,
    lab_result_alerts boolean DEFAULT true NOT NULL,
    imaging_result_alerts boolean DEFAULT true NOT NULL,
    consultation_notifications boolean DEFAULT true NOT NULL,
    profile_visibility character varying(20) DEFAULT 'PRIVATE'::character varying NOT NULL,
    data_sharing_enabled boolean DEFAULT false NOT NULL,
    two_factor_enabled boolean DEFAULT false NOT NULL,
    language character varying(10) DEFAULT 'en'::character varying NOT NULL,
    timezone character varying(50) DEFAULT 'UTC'::character varying,
    theme character varying(20) DEFAULT 'LIGHT'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    critical_alerts boolean DEFAULT true NOT NULL,
    medication_reminders boolean DEFAULT true NOT NULL,
    vitals_alerts boolean DEFAULT true NOT NULL,
    appointment_reminder_1h boolean DEFAULT true NOT NULL,
    appointment_change_alerts boolean DEFAULT true NOT NULL,
    video_call_alerts boolean DEFAULT true NOT NULL,
    message_notifications boolean DEFAULT true NOT NULL,
    care_plan_update_alerts boolean DEFAULT false NOT NULL,
    referral_alerts boolean DEFAULT true NOT NULL,
    login_alerts boolean DEFAULT true NOT NULL,
    access_change_alerts boolean DEFAULT true NOT NULL,
    weekly_summary boolean DEFAULT false NOT NULL,
    CONSTRAINT user_settings_profile_visibility_check CHECK (((profile_visibility)::text = ANY ((ARRAY['PRIVATE'::character varying, 'CONTACTS_ONLY'::character varying, 'PUBLIC'::character varying])::text[]))),
    CONSTRAINT user_settings_theme_check CHECK (((theme)::text = ANY ((ARRAY['LIGHT'::character varying, 'DARK'::character varying])::text[])))
);


--
-- Name: user_settings_setting_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_settings_setting_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_settings_setting_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_settings_setting_id_seq OWNED BY public.user_settings.setting_id;


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    user_id integer NOT NULL,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    phone character varying(20),
    email character varying(150),
    password_hash character varying(255),
    role character varying(20) DEFAULT 'PATIENT'::character varying NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    system_id character varying(20) NOT NULL,
    CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['PATIENT'::character varying, 'DOCTOR'::character varying, 'CAREGIVER'::character varying, 'ADMIN'::character varying])::text[])))
);


--
-- Name: TABLE users; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.users IS 'Core authentication and identity';


--
-- Name: users_user_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_user_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_user_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_user_id_seq OWNED BY public.users.user_id;


--
-- Name: v_doctor_workload; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_doctor_workload AS
 SELECT d.system_id,
    u.first_name,
    u.last_name,
    d.specialization,
    count(DISTINCT a.appointment_id) AS upcoming_appointments,
    count(DISTINCT c.consultation_id) AS active_consultations,
    count(DISTINCT ai.alert_id) AS unread_ai_alerts
   FROM ((((public.doctors d
     JOIN public.users u ON (((u.system_id)::text = (d.system_id)::text)))
     LEFT JOIN public.appointments a ON ((((a.doctor_system_id)::text = (d.system_id)::text) AND ((a.status)::text = 'SCHEDULED'::text))))
     LEFT JOIN public.consultations c ON ((((c.doctor_system_id)::text = (d.system_id)::text) AND ((c.status)::text = 'IN_PROGRESS'::text))))
     LEFT JOIN public.ai_alerts ai ON ((((ai.doctor_system_id)::text = (d.system_id)::text) AND (ai.is_acknowledged = false))))
  GROUP BY d.system_id, u.first_name, u.last_name, d.specialization;


--
-- Name: v_patient_dashboard; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_patient_dashboard AS
 SELECT p.system_id,
    u.first_name,
    u.last_name,
    p.health_status,
    count(DISTINCT a.appointment_id) AS total_appointments,
    count(DISTINCT rx.prescription_id) AS active_prescriptions,
    count(DISTINCT lr.result_id) AS lab_results,
    count(DISTINCT ai.alert_id) AS ai_alerts_unread
   FROM (((((public.patients p
     JOIN public.users u ON (((u.system_id)::text = (p.system_id)::text)))
     LEFT JOIN public.appointments a ON ((((a.patient_system_id)::text = (p.system_id)::text) AND ((a.status)::text = 'SCHEDULED'::text))))
     LEFT JOIN public.prescriptions rx ON ((((rx.patient_system_id)::text = (p.system_id)::text) AND ((rx.status)::text = 'ACTIVE'::text))))
     LEFT JOIN public.lab_results lr ON (((lr.patient_system_id)::text = (p.system_id)::text)))
     LEFT JOIN public.ai_alerts ai ON ((((ai.patient_system_id)::text = (p.system_id)::text) AND (ai.is_acknowledged = false))))
  GROUP BY p.system_id, u.first_name, u.last_name, p.health_status;


--
-- Name: ai_alerts alert_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_alerts ALTER COLUMN alert_id SET DEFAULT nextval('public.ai_alerts_alert_id_seq'::regclass);


--
-- Name: appointments appointment_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments ALTER COLUMN appointment_id SET DEFAULT nextval('public.appointments_appointment_id_seq'::regclass);


--
-- Name: audit_log audit_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log ALTER COLUMN audit_id SET DEFAULT nextval('public.audit_log_audit_id_seq'::regclass);


--
-- Name: care_plans plan_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_plans ALTER COLUMN plan_id SET DEFAULT nextval('public.care_plans_plan_id_seq'::regclass);


--
-- Name: care_team care_team_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_team ALTER COLUMN care_team_id SET DEFAULT nextval('public.care_team_team_id_seq'::regclass);


--
-- Name: caregiver_activity_log log_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregiver_activity_log ALTER COLUMN log_id SET DEFAULT nextval('public.caregiver_activity_log_log_id_seq'::regclass);


--
-- Name: caregiver_alerts alert_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregiver_alerts ALTER COLUMN alert_id SET DEFAULT nextval('public.caregiver_alerts_alert_id_seq'::regclass);


--
-- Name: caregivers caregiver_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregivers ALTER COLUMN caregiver_id SET DEFAULT nextval('public.caregivers_caregiver_id_seq'::regclass);


--
-- Name: consent_records consent_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_records ALTER COLUMN consent_id SET DEFAULT nextval('public.consent_records_consent_id_seq'::regclass);


--
-- Name: consultations consultation_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations ALTER COLUMN consultation_id SET DEFAULT nextval('public.consultations_consultation_id_seq'::regclass);


--
-- Name: dependent_access access_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependent_access ALTER COLUMN access_id SET DEFAULT nextval('public.dependent_access_access_id_seq'::regclass);


--
-- Name: disease_detection detection_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disease_detection ALTER COLUMN detection_id SET DEFAULT nextval('public.disease_detection_detection_id_seq'::regclass);


--
-- Name: doctors doctor_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors ALTER COLUMN doctor_id SET DEFAULT nextval('public.doctors_doctor_id_seq'::regclass);


--
-- Name: documents document_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents ALTER COLUMN document_id SET DEFAULT nextval('public.documents_document_id_seq'::regclass);


--
-- Name: emergency_contacts contact_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.emergency_contacts ALTER COLUMN contact_id SET DEFAULT nextval('public.emergency_contacts_contact_id_seq'::regclass);


--
-- Name: facilities facility_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.facilities ALTER COLUMN facility_id SET DEFAULT nextval('public.facilities_facility_id_seq'::regclass);


--
-- Name: health_metrics metric_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.health_metrics ALTER COLUMN metric_id SET DEFAULT nextval('public.health_metrics_metric_id_seq'::regclass);


--
-- Name: imaging_results imaging_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results ALTER COLUMN imaging_id SET DEFAULT nextval('public.imaging_results_imaging_id_seq'::regclass);


--
-- Name: insurance_claims claim_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.insurance_claims ALTER COLUMN claim_id SET DEFAULT nextval('public.insurance_claims_claim_id_seq'::regclass);


--
-- Name: invoices invoice_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices ALTER COLUMN invoice_id SET DEFAULT nextval('public.invoices_invoice_id_seq'::regclass);


--
-- Name: lab_results result_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results ALTER COLUMN result_id SET DEFAULT nextval('public.lab_results_result_id_seq'::regclass);


--
-- Name: medical_records record_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medical_records ALTER COLUMN record_id SET DEFAULT nextval('public.medical_records_record_id_seq'::regclass);


--
-- Name: medication_schedules schedule_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medication_schedules ALTER COLUMN schedule_id SET DEFAULT nextval('public.medication_schedules_schedule_id_seq'::regclass);


--
-- Name: message_threads thread_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_threads ALTER COLUMN thread_id SET DEFAULT nextval('public.message_threads_thread_id_seq'::regclass);


--
-- Name: messages message_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages ALTER COLUMN message_id SET DEFAULT nextval('public.messages_message_id_seq'::regclass);


--
-- Name: notifications notification_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications ALTER COLUMN notification_id SET DEFAULT nextval('public.notifications_notification_id_seq'::regclass);


--
-- Name: patients patient_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patients ALTER COLUMN patient_id SET DEFAULT nextval('public.patients_patient_id_seq'::regclass);


--
-- Name: payment_methods method_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_methods ALTER COLUMN method_id SET DEFAULT nextval('public.payment_methods_method_id_seq'::regclass);


--
-- Name: payments payment_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments ALTER COLUMN payment_id SET DEFAULT nextval('public.payments_payment_id_seq'::regclass);


--
-- Name: permissions permission_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions ALTER COLUMN permission_id SET DEFAULT nextval('public.permissions_permission_id_seq'::regclass);


--
-- Name: prescriptions prescription_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prescriptions ALTER COLUMN prescription_id SET DEFAULT nextval('public.prescriptions_prescription_id_seq'::regclass);


--
-- Name: referrals referral_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals ALTER COLUMN referral_id SET DEFAULT nextval('public.referrals_referral_id_seq'::regclass);


--
-- Name: refresh_tokens token_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens ALTER COLUMN token_id SET DEFAULT nextval('public.refresh_tokens_token_id_seq'::regclass);


--
-- Name: reminders reminder_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reminders ALTER COLUMN reminder_id SET DEFAULT nextval('public.reminders_reminder_id_seq'::regclass);


--
-- Name: symptom_logs symptom_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symptom_logs ALTER COLUMN symptom_id SET DEFAULT nextval('public.symptom_logs_symptom_id_seq'::regclass);


--
-- Name: user_settings setting_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_settings ALTER COLUMN setting_id SET DEFAULT nextval('public.user_settings_setting_id_seq'::regclass);


--
-- Name: users user_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN user_id SET DEFAULT nextval('public.users_user_id_seq'::regclass);


--
-- Name: ai_alerts ai_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_alerts
    ADD CONSTRAINT ai_alerts_pkey PRIMARY KEY (alert_id);


--
-- Name: appointments appointments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT appointments_pkey PRIMARY KEY (appointment_id);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (audit_id);


--
-- Name: care_plans care_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_plans
    ADD CONSTRAINT care_plans_pkey PRIMARY KEY (plan_id);


--
-- Name: care_team care_team_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_team
    ADD CONSTRAINT care_team_pkey PRIMARY KEY (care_team_id);


--
-- Name: caregiver_activity_log caregiver_activity_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregiver_activity_log
    ADD CONSTRAINT caregiver_activity_log_pkey PRIMARY KEY (log_id);


--
-- Name: caregiver_alerts caregiver_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregiver_alerts
    ADD CONSTRAINT caregiver_alerts_pkey PRIMARY KEY (alert_id);


--
-- Name: caregivers caregivers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregivers
    ADD CONSTRAINT caregivers_pkey PRIMARY KEY (caregiver_id);


--
-- Name: caregivers caregivers_system_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregivers
    ADD CONSTRAINT caregivers_system_id_key UNIQUE (system_id);


--
-- Name: consent_records consent_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT consent_records_pkey PRIMARY KEY (consent_id);


--
-- Name: consultations consultations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_pkey PRIMARY KEY (consultation_id);


--
-- Name: dependent_access dependent_access_caregiver_system_id_patient_system_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependent_access
    ADD CONSTRAINT dependent_access_caregiver_system_id_patient_system_id_key UNIQUE (caregiver_system_id, patient_system_id);


--
-- Name: dependent_access dependent_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependent_access
    ADD CONSTRAINT dependent_access_pkey PRIMARY KEY (access_id);


--
-- Name: disease_detection disease_detection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disease_detection
    ADD CONSTRAINT disease_detection_pkey PRIMARY KEY (detection_id);


--
-- Name: doctors doctors_license_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT doctors_license_no_key UNIQUE (license_no);


--
-- Name: doctors doctors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT doctors_pkey PRIMARY KEY (doctor_id);


--
-- Name: doctors doctors_system_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT doctors_system_id_key UNIQUE (system_id);


--
-- Name: documents documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (document_id);


--
-- Name: emergency_contacts emergency_contacts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.emergency_contacts
    ADD CONSTRAINT emergency_contacts_pkey PRIMARY KEY (contact_id);


--
-- Name: facilities facilities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.facilities
    ADD CONSTRAINT facilities_pkey PRIMARY KEY (facility_id);


--
-- Name: health_metrics health_metrics_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.health_metrics
    ADD CONSTRAINT health_metrics_pkey PRIMARY KEY (metric_id);


--
-- Name: imaging_results imaging_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results
    ADD CONSTRAINT imaging_results_pkey PRIMARY KEY (imaging_id);


--
-- Name: insurance_claims insurance_claims_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.insurance_claims
    ADD CONSTRAINT insurance_claims_pkey PRIMARY KEY (claim_id);


--
-- Name: invoices invoices_invoice_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_invoice_no_key UNIQUE (invoice_no);


--
-- Name: invoices invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_pkey PRIMARY KEY (invoice_id);


--
-- Name: lab_results lab_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results
    ADD CONSTRAINT lab_results_pkey PRIMARY KEY (result_id);


--
-- Name: medical_records medical_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medical_records
    ADD CONSTRAINT medical_records_pkey PRIMARY KEY (record_id);


--
-- Name: medication_schedules medication_schedules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medication_schedules
    ADD CONSTRAINT medication_schedules_pkey PRIMARY KEY (schedule_id);


--
-- Name: message_threads message_threads_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_threads
    ADD CONSTRAINT message_threads_pkey PRIMARY KEY (thread_id);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (message_id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (notification_id);


--
-- Name: patients patients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patients
    ADD CONSTRAINT patients_pkey PRIMARY KEY (patient_id);


--
-- Name: patients patients_system_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patients
    ADD CONSTRAINT patients_system_id_key UNIQUE (system_id);


--
-- Name: payment_methods payment_methods_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_methods
    ADD CONSTRAINT payment_methods_pkey PRIMARY KEY (method_id);


--
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (payment_id);


--
-- Name: payments payments_transaction_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_transaction_id_key UNIQUE (transaction_id);


--
-- Name: permissions permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (permission_id);


--
-- Name: prescriptions prescriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prescriptions
    ADD CONSTRAINT prescriptions_pkey PRIMARY KEY (prescription_id);


--
-- Name: referrals referrals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_pkey PRIMARY KEY (referral_id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (token_id);


--
-- Name: refresh_tokens refresh_tokens_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash);


--
-- Name: reminders reminders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reminders
    ADD CONSTRAINT reminders_pkey PRIMARY KEY (reminder_id);


--
-- Name: symptom_logs symptom_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symptom_logs
    ADD CONSTRAINT symptom_logs_pkey PRIMARY KEY (symptom_id);


--
-- Name: user_settings user_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_settings
    ADD CONSTRAINT user_settings_pkey PRIMARY KEY (setting_id);


--
-- Name: user_settings user_settings_system_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_settings
    ADD CONSTRAINT user_settings_system_id_key UNIQUE (system_id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_phone_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_phone_key UNIQUE (phone);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);


--
-- Name: users users_system_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_system_id_key UNIQUE (system_id);


--
-- Name: idx_ai_ack; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_ack ON public.ai_alerts USING btree (is_acknowledged);


--
-- Name: idx_ai_priority; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_priority ON public.ai_alerts USING btree (priority);


--
-- Name: idx_appointments_doctor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_appointments_doctor ON public.appointments USING btree (doctor_system_id);


--
-- Name: idx_appointments_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_appointments_patient ON public.appointments USING btree (patient_system_id);


--
-- Name: idx_appt_facility; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_appt_facility ON public.appointments USING btree (facility_id);


--
-- Name: idx_appt_scheduled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_appt_scheduled ON public.appointments USING btree (scheduled_at);


--
-- Name: idx_appt_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_appt_status ON public.appointments USING btree (status);


--
-- Name: idx_audit_log_action; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_action ON public.audit_log USING btree (action, "timestamp");


--
-- Name: idx_audit_log_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_patient ON public.audit_log USING btree (patient_system_id, "timestamp");


--
-- Name: idx_audit_log_system_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_system_id ON public.audit_log USING btree (system_id);


--
-- Name: idx_audit_resource; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_resource ON public.audit_log USING btree (resource_type, resource_id);


--
-- Name: idx_audit_timestamp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_timestamp ON public.audit_log USING btree ("timestamp");


--
-- Name: idx_care_plans_doctor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_care_plans_doctor ON public.care_plans USING btree (doctor_system_id);


--
-- Name: idx_care_plans_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_care_plans_patient ON public.care_plans USING btree (patient_system_id);


--
-- Name: idx_care_team_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_care_team_active ON public.care_team USING btree (provider_system_id, patient_system_id) WHERE is_active;


--
-- Name: idx_care_team_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_care_team_patient ON public.care_team USING btree (patient_system_id);


--
-- Name: idx_care_team_provider; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_care_team_provider ON public.care_team USING btree (provider_system_id);


--
-- Name: idx_caregivers_system_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_caregivers_system_id ON public.caregivers USING btree (system_id);


--
-- Name: idx_cga_caregiver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cga_caregiver ON public.caregiver_alerts USING btree (caregiver_id);


--
-- Name: idx_cga_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cga_patient ON public.caregiver_alerts USING btree (patient_id);


--
-- Name: idx_cga_severity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cga_severity ON public.caregiver_alerts USING btree (severity);


--
-- Name: idx_cgal_caregiver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cgal_caregiver ON public.caregiver_activity_log USING btree (caregiver_id);


--
-- Name: idx_cgal_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cgal_patient ON public.caregiver_activity_log USING btree (patient_id);


--
-- Name: idx_cgal_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cgal_time ON public.caregiver_activity_log USING btree ("timestamp");


--
-- Name: idx_claim_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_claim_patient ON public.insurance_claims USING btree (patient_id);


--
-- Name: idx_claim_payment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_claim_payment ON public.insurance_claims USING btree (payment_id);


--
-- Name: idx_claim_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_claim_status ON public.insurance_claims USING btree (status);


--
-- Name: idx_consent_caregiver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consent_caregiver ON public.consent_records USING btree (caregiver_id);


--
-- Name: idx_consent_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consent_patient ON public.consent_records USING btree (patient_id);


--
-- Name: idx_consult_appt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consult_appt ON public.consultations USING btree (appointment_id);


--
-- Name: idx_consult_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consult_status ON public.consultations USING btree (status);


--
-- Name: idx_consultations_doctor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consultations_doctor ON public.consultations USING btree (doctor_system_id);


--
-- Name: idx_consultations_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consultations_patient ON public.consultations USING btree (patient_system_id);


--
-- Name: idx_dd_confirmed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dd_confirmed ON public.disease_detection USING btree (is_confirmed);


--
-- Name: idx_dd_risk; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dd_risk ON public.disease_detection USING btree (risk_level);


--
-- Name: idx_dependent_access_caregiver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dependent_access_caregiver ON public.dependent_access USING btree (caregiver_system_id);


--
-- Name: idx_dependent_access_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dependent_access_patient ON public.dependent_access USING btree (patient_system_id);


--
-- Name: idx_doc_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_type ON public.documents USING btree (document_type);


--
-- Name: idx_doctors_system_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doctors_system_id ON public.doctors USING btree (system_id);


--
-- Name: idx_documents_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_patient ON public.documents USING btree (patient_system_id);


--
-- Name: idx_documents_uploader; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_documents_uploader ON public.documents USING btree (uploaded_by_system_id);


--
-- Name: idx_emergency_contacts_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_emergency_contacts_patient ON public.emergency_contacts USING btree (patient_system_id);


--
-- Name: idx_hm_abnormal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_hm_abnormal ON public.health_metrics USING btree (is_abnormal);


--
-- Name: idx_hm_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_hm_patient ON public.health_metrics USING btree (patient_id);


--
-- Name: idx_hm_recorded; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_hm_recorded ON public.health_metrics USING btree (recorded_at);


--
-- Name: idx_hm_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_hm_type ON public.health_metrics USING btree (type);


--
-- Name: idx_imaging_results_doctor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_imaging_results_doctor ON public.imaging_results USING btree (doctor_system_id);


--
-- Name: idx_imaging_results_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_imaging_results_patient ON public.imaging_results USING btree (patient_system_id);


--
-- Name: idx_img_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_img_type ON public.imaging_results USING btree (imaging_type);


--
-- Name: idx_inv_appointment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inv_appointment ON public.invoices USING btree (appointment_id);


--
-- Name: idx_inv_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inv_patient ON public.invoices USING btree (patient_id);


--
-- Name: idx_inv_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inv_status ON public.invoices USING btree (status);


--
-- Name: idx_lab_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lab_date ON public.lab_results USING btree (test_date);


--
-- Name: idx_lab_results_doctor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lab_results_doctor ON public.lab_results USING btree (doctor_system_id);


--
-- Name: idx_lab_results_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lab_results_patient ON public.lab_results USING btree (patient_system_id);


--
-- Name: idx_lab_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lab_status ON public.lab_results USING btree (status);


--
-- Name: idx_medical_records_doctor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_medical_records_doctor ON public.medical_records USING btree (doctor_system_id);


--
-- Name: idx_medical_records_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_medical_records_patient ON public.medical_records USING btree (patient_system_id);


--
-- Name: idx_message_threads_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_message_threads_patient ON public.message_threads USING btree (patient_system_id);


--
-- Name: idx_messages_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_patient ON public.messages USING btree (patient_system_id);


--
-- Name: idx_messages_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_recipient ON public.messages USING btree (recipient_system_id);


--
-- Name: idx_messages_sender; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_sender ON public.messages USING btree (sender_system_id);


--
-- Name: idx_mr_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mr_type ON public.medical_records USING btree (record_type);


--
-- Name: idx_ms_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ms_date ON public.medication_schedules USING btree (schedule_date);


--
-- Name: idx_ms_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ms_patient ON public.medication_schedules USING btree (patient_id);


--
-- Name: idx_ms_prescription; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ms_prescription ON public.medication_schedules USING btree (prescription_id);


--
-- Name: idx_msg_thread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_thread ON public.messages USING btree (thread_id);


--
-- Name: idx_notif_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notif_created ON public.notifications USING btree (created_at);


--
-- Name: idx_notif_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notif_type ON public.notifications USING btree (type);


--
-- Name: idx_notif_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notif_unread ON public.notifications USING btree (is_read);


--
-- Name: idx_notifications_system_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_system_id ON public.notifications USING btree (system_id);


--
-- Name: idx_patients_health_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_patients_health_status ON public.patients USING btree (health_status);


--
-- Name: idx_patients_system_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_patients_system_id ON public.patients USING btree (system_id);


--
-- Name: idx_pay_appointment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pay_appointment ON public.payments USING btree (appointment_id);


--
-- Name: idx_pay_invoice; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pay_invoice ON public.payments USING btree (invoice_id);


--
-- Name: idx_pay_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pay_patient ON public.payments USING btree (patient_id);


--
-- Name: idx_pay_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pay_status ON public.payments USING btree (status);


--
-- Name: idx_perm_caregiver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_perm_caregiver ON public.permissions USING btree (caregiver_id);


--
-- Name: idx_perm_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_perm_patient ON public.permissions USING btree (patient_id);


--
-- Name: idx_pm_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pm_user ON public.payment_methods USING btree (user_id);


--
-- Name: idx_prescriptions_doctor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_prescriptions_doctor ON public.prescriptions USING btree (doctor_system_id);


--
-- Name: idx_prescriptions_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_prescriptions_patient ON public.prescriptions USING btree (patient_system_id);


--
-- Name: idx_referrals_doctor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_referrals_doctor ON public.referrals USING btree (referring_doctor_system_id);


--
-- Name: idx_referrals_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_referrals_patient ON public.referrals USING btree (patient_system_id);


--
-- Name: idx_referrals_specialist; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_referrals_specialist ON public.referrals USING btree (specialist_system_id);


--
-- Name: idx_refresh_tokens_system_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_system_id ON public.refresh_tokens USING btree (system_id);


--
-- Name: idx_rem_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rem_active ON public.reminders USING btree (is_active);


--
-- Name: idx_rem_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rem_patient ON public.reminders USING btree (patient_id);


--
-- Name: idx_rx_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rx_status ON public.prescriptions USING btree (status);


--
-- Name: idx_sl_logged; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sl_logged ON public.symptom_logs USING btree (logged_at);


--
-- Name: idx_sl_patient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sl_patient ON public.symptom_logs USING btree (patient_id);


--
-- Name: idx_sl_severity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sl_severity ON public.symptom_logs USING btree (severity);


--
-- Name: idx_thread_last; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_thread_last ON public.message_threads USING btree (last_message_at);


--
-- Name: idx_users_system_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_system_id ON public.users USING btree (system_id);


--
-- Name: appointments update_appointments_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_appointments_updated_at BEFORE UPDATE ON public.appointments FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: care_plans update_care_plans_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_care_plans_updated_at BEFORE UPDATE ON public.care_plans FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: medical_records update_medical_records_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_medical_records_updated_at BEFORE UPDATE ON public.medical_records FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: user_settings update_user_settings_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_user_settings_updated_at BEFORE UPDATE ON public.user_settings FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: users update_users_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: ai_alerts ai_alerts_acknowledged_by_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_alerts
    ADD CONSTRAINT ai_alerts_acknowledged_by_system_id_fkey FOREIGN KEY (acknowledged_by_system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: ai_alerts ai_alerts_detection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_alerts
    ADD CONSTRAINT ai_alerts_detection_id_fkey FOREIGN KEY (detection_id) REFERENCES public.disease_detection(detection_id) ON DELETE SET NULL;


--
-- Name: ai_alerts ai_alerts_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_alerts
    ADD CONSTRAINT ai_alerts_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: ai_alerts ai_alerts_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_alerts
    ADD CONSTRAINT ai_alerts_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: appointments appointments_doctor_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT appointments_doctor_profile_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.doctors(system_id) ON DELETE RESTRICT;


--
-- Name: appointments appointments_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT appointments_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: appointments appointments_facility_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT appointments_facility_id_fkey FOREIGN KEY (facility_id) REFERENCES public.facilities(facility_id) ON DELETE SET NULL;


--
-- Name: appointments appointments_patient_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT appointments_patient_profile_fkey FOREIGN KEY (patient_system_id) REFERENCES public.patients(system_id) ON DELETE RESTRICT;


--
-- Name: appointments appointments_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT appointments_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: audit_log audit_log_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_system_id_fkey FOREIGN KEY (system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: care_plans care_plans_consultation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_plans
    ADD CONSTRAINT care_plans_consultation_id_fkey FOREIGN KEY (consultation_id) REFERENCES public.consultations(consultation_id) ON DELETE SET NULL;


--
-- Name: care_plans care_plans_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_plans
    ADD CONSTRAINT care_plans_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: care_plans care_plans_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_plans
    ADD CONSTRAINT care_plans_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: care_team care_team_facility_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_team
    ADD CONSTRAINT care_team_facility_id_fkey FOREIGN KEY (facility_id) REFERENCES public.facilities(facility_id) ON DELETE SET NULL;


--
-- Name: care_team care_team_patient_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_team
    ADD CONSTRAINT care_team_patient_profile_fkey FOREIGN KEY (patient_system_id) REFERENCES public.patients(system_id) ON DELETE CASCADE;


--
-- Name: care_team care_team_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_team
    ADD CONSTRAINT care_team_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: care_team care_team_provider_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.care_team
    ADD CONSTRAINT care_team_provider_system_id_fkey FOREIGN KEY (provider_system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: caregiver_activity_log caregiver_activity_log_caregiver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregiver_activity_log
    ADD CONSTRAINT caregiver_activity_log_caregiver_id_fkey FOREIGN KEY (caregiver_id) REFERENCES public.caregivers(caregiver_id) ON DELETE CASCADE;


--
-- Name: caregiver_activity_log caregiver_activity_log_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregiver_activity_log
    ADD CONSTRAINT caregiver_activity_log_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE CASCADE;


--
-- Name: caregiver_alerts caregiver_alerts_caregiver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregiver_alerts
    ADD CONSTRAINT caregiver_alerts_caregiver_id_fkey FOREIGN KEY (caregiver_id) REFERENCES public.caregivers(caregiver_id) ON DELETE CASCADE;


--
-- Name: caregiver_alerts caregiver_alerts_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregiver_alerts
    ADD CONSTRAINT caregiver_alerts_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE CASCADE;


--
-- Name: caregivers caregivers_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.caregivers
    ADD CONSTRAINT caregivers_system_id_fkey FOREIGN KEY (system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: consent_records consent_records_caregiver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT consent_records_caregiver_id_fkey FOREIGN KEY (caregiver_id) REFERENCES public.caregivers(caregiver_id) ON DELETE SET NULL;


--
-- Name: consent_records consent_records_doctor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT consent_records_doctor_id_fkey FOREIGN KEY (doctor_id) REFERENCES public.doctors(doctor_id) ON DELETE SET NULL;


--
-- Name: consent_records consent_records_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_records
    ADD CONSTRAINT consent_records_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE RESTRICT;


--
-- Name: consultations consultations_appointment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_appointment_id_fkey FOREIGN KEY (appointment_id) REFERENCES public.appointments(appointment_id) ON DELETE RESTRICT;


--
-- Name: consultations consultations_doctor_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_doctor_profile_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.doctors(system_id) ON DELETE RESTRICT;


--
-- Name: consultations consultations_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: consultations consultations_patient_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_patient_profile_fkey FOREIGN KEY (patient_system_id) REFERENCES public.patients(system_id) ON DELETE RESTRICT;


--
-- Name: consultations consultations_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: dependent_access dependent_access_authorized_by_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependent_access
    ADD CONSTRAINT dependent_access_authorized_by_system_id_fkey FOREIGN KEY (authorized_by_system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: dependent_access dependent_access_caregiver_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependent_access
    ADD CONSTRAINT dependent_access_caregiver_profile_fkey FOREIGN KEY (caregiver_system_id) REFERENCES public.caregivers(system_id) ON DELETE CASCADE;


--
-- Name: dependent_access dependent_access_caregiver_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependent_access
    ADD CONSTRAINT dependent_access_caregiver_system_id_fkey FOREIGN KEY (caregiver_system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: dependent_access dependent_access_patient_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependent_access
    ADD CONSTRAINT dependent_access_patient_profile_fkey FOREIGN KEY (patient_system_id) REFERENCES public.patients(system_id) ON DELETE CASCADE;


--
-- Name: dependent_access dependent_access_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependent_access
    ADD CONSTRAINT dependent_access_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: disease_detection disease_detection_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disease_detection
    ADD CONSTRAINT disease_detection_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: disease_detection disease_detection_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disease_detection
    ADD CONSTRAINT disease_detection_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: disease_detection disease_detection_reviewed_by_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disease_detection
    ADD CONSTRAINT disease_detection_reviewed_by_system_id_fkey FOREIGN KEY (reviewed_by_system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: disease_detection disease_detection_symptom_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.disease_detection
    ADD CONSTRAINT disease_detection_symptom_id_fkey FOREIGN KEY (symptom_id) REFERENCES public.symptom_logs(symptom_id) ON DELETE SET NULL;


--
-- Name: doctors doctors_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT doctors_system_id_fkey FOREIGN KEY (system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: documents documents_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: documents documents_uploaded_by_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_uploaded_by_system_id_fkey FOREIGN KEY (uploaded_by_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: emergency_contacts emergency_contacts_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.emergency_contacts
    ADD CONSTRAINT emergency_contacts_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: health_metrics health_metrics_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.health_metrics
    ADD CONSTRAINT health_metrics_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE CASCADE;


--
-- Name: imaging_results imaging_results_consultation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results
    ADD CONSTRAINT imaging_results_consultation_id_fkey FOREIGN KEY (consultation_id) REFERENCES public.consultations(consultation_id) ON DELETE SET NULL;


--
-- Name: imaging_results imaging_results_doctor_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results
    ADD CONSTRAINT imaging_results_doctor_profile_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.doctors(system_id) ON DELETE RESTRICT;


--
-- Name: imaging_results imaging_results_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results
    ADD CONSTRAINT imaging_results_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: imaging_results imaging_results_patient_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results
    ADD CONSTRAINT imaging_results_patient_profile_fkey FOREIGN KEY (patient_system_id) REFERENCES public.patients(system_id) ON DELETE RESTRICT;


--
-- Name: imaging_results imaging_results_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results
    ADD CONSTRAINT imaging_results_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: imaging_results imaging_results_reviewed_by_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results
    ADD CONSTRAINT imaging_results_reviewed_by_profile_fkey FOREIGN KEY (reviewed_by_system_id) REFERENCES public.doctors(system_id) ON DELETE SET NULL;


--
-- Name: imaging_results imaging_results_reviewed_by_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imaging_results
    ADD CONSTRAINT imaging_results_reviewed_by_system_id_fkey FOREIGN KEY (reviewed_by_system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: insurance_claims insurance_claims_invoice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.insurance_claims
    ADD CONSTRAINT insurance_claims_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(invoice_id) ON DELETE SET NULL;


--
-- Name: insurance_claims insurance_claims_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.insurance_claims
    ADD CONSTRAINT insurance_claims_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE RESTRICT;


--
-- Name: insurance_claims insurance_claims_payment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.insurance_claims
    ADD CONSTRAINT insurance_claims_payment_id_fkey FOREIGN KEY (payment_id) REFERENCES public.payments(payment_id) ON DELETE SET NULL;


--
-- Name: invoices invoices_appointment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_appointment_id_fkey FOREIGN KEY (appointment_id) REFERENCES public.appointments(appointment_id) ON DELETE SET NULL;


--
-- Name: invoices invoices_consultation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_consultation_id_fkey FOREIGN KEY (consultation_id) REFERENCES public.consultations(consultation_id) ON DELETE SET NULL;


--
-- Name: invoices invoices_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE RESTRICT;


--
-- Name: lab_results lab_results_consultation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results
    ADD CONSTRAINT lab_results_consultation_id_fkey FOREIGN KEY (consultation_id) REFERENCES public.consultations(consultation_id) ON DELETE SET NULL;


--
-- Name: lab_results lab_results_doctor_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results
    ADD CONSTRAINT lab_results_doctor_profile_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.doctors(system_id) ON DELETE RESTRICT;


--
-- Name: lab_results lab_results_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results
    ADD CONSTRAINT lab_results_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: lab_results lab_results_patient_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results
    ADD CONSTRAINT lab_results_patient_profile_fkey FOREIGN KEY (patient_system_id) REFERENCES public.patients(system_id) ON DELETE RESTRICT;


--
-- Name: lab_results lab_results_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results
    ADD CONSTRAINT lab_results_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: lab_results lab_results_reviewed_by_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results
    ADD CONSTRAINT lab_results_reviewed_by_profile_fkey FOREIGN KEY (reviewed_by_system_id) REFERENCES public.doctors(system_id) ON DELETE SET NULL;


--
-- Name: lab_results lab_results_reviewed_by_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lab_results
    ADD CONSTRAINT lab_results_reviewed_by_system_id_fkey FOREIGN KEY (reviewed_by_system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: medical_records medical_records_consultation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medical_records
    ADD CONSTRAINT medical_records_consultation_id_fkey FOREIGN KEY (consultation_id) REFERENCES public.consultations(consultation_id) ON DELETE SET NULL;


--
-- Name: medical_records medical_records_doctor_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medical_records
    ADD CONSTRAINT medical_records_doctor_profile_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.doctors(system_id) ON DELETE RESTRICT;


--
-- Name: medical_records medical_records_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medical_records
    ADD CONSTRAINT medical_records_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: medical_records medical_records_patient_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medical_records
    ADD CONSTRAINT medical_records_patient_profile_fkey FOREIGN KEY (patient_system_id) REFERENCES public.patients(system_id) ON DELETE RESTRICT;


--
-- Name: medical_records medical_records_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medical_records
    ADD CONSTRAINT medical_records_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: medication_schedules medication_schedules_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medication_schedules
    ADD CONSTRAINT medication_schedules_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE CASCADE;


--
-- Name: medication_schedules medication_schedules_prescription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.medication_schedules
    ADD CONSTRAINT medication_schedules_prescription_id_fkey FOREIGN KEY (prescription_id) REFERENCES public.prescriptions(prescription_id) ON DELETE CASCADE;


--
-- Name: message_threads message_threads_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_threads
    ADD CONSTRAINT message_threads_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: messages messages_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: messages messages_recipient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_recipient_system_id_fkey FOREIGN KEY (recipient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: messages messages_sender_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_sender_system_id_fkey FOREIGN KEY (sender_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: messages messages_thread_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_thread_id_fkey FOREIGN KEY (thread_id) REFERENCES public.message_threads(thread_id) ON DELETE CASCADE;


--
-- Name: notifications notifications_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_system_id_fkey FOREIGN KEY (system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: patients patients_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patients
    ADD CONSTRAINT patients_system_id_fkey FOREIGN KEY (system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: payment_methods payment_methods_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_methods
    ADD CONSTRAINT payment_methods_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: payments payments_appointment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_appointment_id_fkey FOREIGN KEY (appointment_id) REFERENCES public.appointments(appointment_id) ON DELETE SET NULL;


--
-- Name: payments payments_invoice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(invoice_id) ON DELETE SET NULL;


--
-- Name: payments payments_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE RESTRICT;


--
-- Name: payments payments_payment_method_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_payment_method_id_fkey FOREIGN KEY (payment_method_id) REFERENCES public.payment_methods(method_id) ON DELETE SET NULL;


--
-- Name: permissions permissions_caregiver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_caregiver_id_fkey FOREIGN KEY (caregiver_id) REFERENCES public.caregivers(caregiver_id) ON DELETE CASCADE;


--
-- Name: permissions permissions_granted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_granted_by_fkey FOREIGN KEY (granted_by) REFERENCES public.users(user_id) ON DELETE RESTRICT;


--
-- Name: permissions permissions_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE CASCADE;


--
-- Name: prescriptions prescriptions_consultation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prescriptions
    ADD CONSTRAINT prescriptions_consultation_id_fkey FOREIGN KEY (consultation_id) REFERENCES public.consultations(consultation_id) ON DELETE SET NULL;


--
-- Name: prescriptions prescriptions_doctor_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prescriptions
    ADD CONSTRAINT prescriptions_doctor_profile_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.doctors(system_id) ON DELETE RESTRICT;


--
-- Name: prescriptions prescriptions_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prescriptions
    ADD CONSTRAINT prescriptions_doctor_system_id_fkey FOREIGN KEY (doctor_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: prescriptions prescriptions_patient_profile_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prescriptions
    ADD CONSTRAINT prescriptions_patient_profile_fkey FOREIGN KEY (patient_system_id) REFERENCES public.patients(system_id) ON DELETE RESTRICT;


--
-- Name: prescriptions prescriptions_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prescriptions
    ADD CONSTRAINT prescriptions_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: referrals referrals_patient_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_patient_system_id_fkey FOREIGN KEY (patient_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: referrals referrals_referring_doctor_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_referring_doctor_system_id_fkey FOREIGN KEY (referring_doctor_system_id) REFERENCES public.users(system_id) ON DELETE RESTRICT;


--
-- Name: referrals referrals_specialist_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_specialist_system_id_fkey FOREIGN KEY (specialist_system_id) REFERENCES public.users(system_id) ON DELETE SET NULL;


--
-- Name: refresh_tokens refresh_tokens_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_system_id_fkey FOREIGN KEY (system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- Name: reminders reminders_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reminders
    ADD CONSTRAINT reminders_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE CASCADE;


--
-- Name: symptom_logs symptom_logs_patient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symptom_logs
    ADD CONSTRAINT symptom_logs_patient_id_fkey FOREIGN KEY (patient_id) REFERENCES public.patients(patient_id) ON DELETE CASCADE;


--
-- Name: user_settings user_settings_system_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_settings
    ADD CONSTRAINT user_settings_system_id_fkey FOREIGN KEY (system_id) REFERENCES public.users(system_id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


