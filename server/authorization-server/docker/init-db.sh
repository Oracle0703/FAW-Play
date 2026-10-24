#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\getenv license_password LICENSE_DATABASE_PASSWORD
\getenv keycloak_password KEYCLOAK_DATABASE_PASSWORD
CREATE ROLE faw_license LOGIN PASSWORD :'license_password';
CREATE DATABASE faw_license OWNER faw_license;
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_password';
CREATE DATABASE keycloak OWNER keycloak;
SQL
