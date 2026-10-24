#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(dirname "$script_dir")
umask 077
if [ -e "$project_dir/.env" ]; then
    echo '.env already exists; refusing to replace database or administrator credentials.' >&2
    exit 1
fi
if [ ! -e "$project_dir/secrets/license-private.pem" ]; then sh "$script_dir/generate-keys.sh"; fi
mkdir -p "$project_dir/updates"
{
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 32)"
    echo "LICENSE_DATABASE_PASSWORD=$(openssl rand -hex 32)"
    echo "KEYCLOAK_DATABASE_PASSWORD=$(openssl rand -hex 32)"
    echo "KEYCLOAK_ADMIN_PASSWORD=$(openssl rand -hex 32)"
    echo 'KEYCLOAK_ADMIN_USERNAME=admin'
    echo "LICENSE_UID=$(id -u)"
    echo "LICENSE_GID=$(id -g)"
} > "$project_dir/.env"
echo "Random credentials saved to $project_dir/.env (0600); no default password is used."
