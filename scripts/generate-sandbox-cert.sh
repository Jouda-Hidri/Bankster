#!/usr/bin/env bash
#
# Issues a TPP transport certificate from the LHV NextGenPSD2 sandbox CA and
# packs it into the PKCS#12 keystore Bankster presents for mutual TLS.
#
# In production this certificate is an eIDAS QWAC obtained from a QTSP; the
# sandbox hands out an equivalent test certificate with no registration.
#
# Usage: scripts/generate-sandbox-cert.sh [output-dir]

set -euo pipefail

BASE_URL="${PSD2_BASE_URL:-https://api.sandbox.lhv.eu/psd2}"
OUT_DIR="${1:-psd2-sandbox}"
PASSWORD="${PSD2_KEYSTORE_PASSWORD:-changeit}"

mkdir -p "$OUT_DIR"
RESPONSE="$OUT_DIR/ca-response.html"

echo "Requesting a test certificate from $BASE_URL ..."
curl -fsS -X POST "$BASE_URL/ui/certificate-authority" -o "$RESPONSE"

# The CA returns an HTML page holding the key and the certificate chain in <pre> blocks.
python3 - "$RESPONSE" "$OUT_DIR" <<'PY'
import html, re, sys

response_path, out_dir = sys.argv[1], sys.argv[2]
page = open(response_path, encoding="utf-8").read()
blocks = [html.unescape(b).strip() for b in re.findall(r"<pre>(.*?)</pre>", page, re.S)]

keys = [b for b in blocks if "PRIVATE KEY" in b]
certs = [b for b in blocks if "CERTIFICATE" in b]
if not keys or not certs:
    sys.exit("The sandbox CA returned no certificate; check %s" % response_path)

open(f"{out_dir}/tpp.key", "w").write(keys[0] + "\n")
open(f"{out_dir}/tpp.crt", "w").write(certs[0] + "\n")          # leaf / TPP certificate
open(f"{out_dir}/ca-chain.crt", "w").write("\n".join(certs[1:]) + "\n")
PY

echo "Building PKCS#12 keystore ..."
openssl pkcs12 -export \
  -inkey "$OUT_DIR/tpp.key" \
  -in "$OUT_DIR/tpp.crt" \
  -certfile "$OUT_DIR/ca-chain.crt" \
  -name tpp \
  -passout "pass:$PASSWORD" \
  -out "$OUT_DIR/tpp-keystore.p12"

TPP_ID="$(openssl x509 -in "$OUT_DIR/tpp.crt" -noout -subject \
  | tr ',/' '\n\n' | grep '2.5.4.97' | cut -d= -f2 | tr -d ' ')"

cat <<EOF

Keystore written to $OUT_DIR/tpp-keystore.p12 (password: $PASSWORD)
TPP id (OID 2.5.4.97): $TPP_ID

Bankster reads this id straight from the certificate, so no further
configuration is needed. Start the app with:

    ./gradlew bootRun

then open http://localhost:8099/psd2
EOF
