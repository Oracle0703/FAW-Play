#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(dirname "$script_dir")
key_dir="$project_dir/secrets"
umask 077
mkdir -p "$key_dir"
if [ -e "$key_dir/license-private.pem" ] || [ -e "$key_dir/license-public.pem" ]; then
    echo 'Key files already exist; refusing to rotate the authorization key.' >&2
    exit 1
fi
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -out "$key_dir/license-private.pem"
openssl pkey -in "$key_dir/license-private.pem" -pubout -out "$key_dir/license-public.pem"
openssl pkey -in "$key_dir/license-private.pem" -pubout -outform DER | openssl base64 -A | tr '+/' '-_' | tr -d '=' > "$key_dir/android-public-key.txt"
echo "Keys saved in $key_dir; android-public-key.txt is the public SPKI build value."
