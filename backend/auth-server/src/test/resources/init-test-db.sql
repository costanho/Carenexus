-- Initialize test database for CareNexus Auth Server
-- This script creates the schema that tests expect

-- Note: Actual tables will be created by Hibernate's ddl-auto: update
-- This script only sets up any database-level configuration needed

-- Set the schema search path
SET search_path TO public;

-- Enable UUID extension if needed (already enabled by default in modern PostgreSQL)
-- CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
