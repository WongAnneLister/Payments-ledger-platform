-- Only used by the disposable PR02 Testcontainers database.
CREATE ROLE payments_app LOGIN PASSWORD 'pr02_test_only';
CREATE SCHEMA platform;
GRANT USAGE ON SCHEMA platform TO payments_app;
