# Bankster [![Build Status](https://travis-ci.org/Jouda-Hidri/Bankster.svg?branch=master)](https://travis-ci.org/Jouda-Hidri/Bankster)

Bankster is a Java application that helps manage expenses by tracking bank transactions.
It connects to banks over the **Berlin Group NextGenPSD2 (XS2A)** open banking API.

## Requirements

* Java 21 (the Gradle toolchain will resolve it)
* Gradle wrapper (included — use `./gradlew`)
* No database or server configuration needed

## Run the project

```
cd path/to/folder

# Clone the project
git clone https://github.com/Jouda-Hidri/Bankster

# Create a TPP transport certificate for the sandbox (once)
./scripts/generate-sandbox-cert.sh

# Run
./gradlew bootRun
```

Then go to http://localhost:8099/

## Connecting to a bank

Bankster is an AISP (account information) client for the Berlin Group
**NextGenPSD2 XS2A** framework — the standard that "open banking in Europe"
usually refers to. Out of the box it targets the
[LHV NextGenPSD2 sandbox](https://partners.lhv.ee/en/open-banking/), which
implements the framework and is open without registration.

### Certificate

NextGenPSD2 identifies the TPP by an **eIDAS QWAC presented as a TLS client
certificate**. Calls without one are rejected even with a valid token. In
production the certificate comes from a QTSP; for the sandbox,
`scripts/generate-sandbox-cert.sh` obtains an equivalent test certificate and
writes `psd2-sandbox/tpp-keystore.p12`.

The TPP authorisation number is read out of the certificate subject
(organizationIdentifier, OID 2.5.4.97) and used as the OAuth2 `client_id`, so no
extra configuration is required. `psd2-sandbox/` is git-ignored — it holds a
private key.

### Flow

Open http://localhost:8099/psd2 and follow "Connect to the bank":

1. **OAuth2 pre-step** — the PSU is redirected to the bank and logs in. In the
   LHV sandbox, pick the **Sandbox** authentication method and the demo user
   *Liis-Mari Männik*.
2. **Consent** — Bankster creates an AIS consent (`POST /v1/consents`) and
   redirects the PSU to the bank's `scaRedirect` URL. The PSU ticks the accounts
   to share and signs, again with the **Sandbox** method.
3. **Data** — once the consent status is `valid`, the bank returns the PSU to
   Bankster, which then reads `/v1/accounts`, `/v1/accounts/{id}/balances` and
   `/v1/accounts/{id}/transactions`.

Step 2 is a human step by design: PSD2 requires strong customer authentication
in the bank's own UI, so it happens in the browser rather than in code.

### Manual test walkthrough

```
./scripts/generate-sandbox-cert.sh     # once — writes psd2-sandbox/tpp-keystore.p12
./gradlew bootRun
```

1. Open <http://localhost:8099/psd2>. The page should show the sandbox URL and a
   TPP id like `PSDEE-LHVTEST-xxxxxx` read out of your certificate. If instead
   the app failed to start with "PSD2 keystore not found", the script has not
   been run.
2. Click **Connect to the bank** → you land on LHV's login screen.
3. Choose **Sandbox** as the authentication method and the user
   *Liis-Mari Männik*, then submit.
4. You are returned to Bankster, which creates the consent and immediately
   forwards you to the bank's consent screen. Select the accounts and sign,
   again with **Sandbox**.
5. You end up on `/psd2/accounts` listing the demo IBANs
   (`EE717700771001735865` and friends) with balances. Click **Transactions** on
   any account for the last 90 days of bookings.

The demo accounts are shared and reset periodically, so balances and
transactions differ between runs; an account with no movements in the window
shows an empty table rather than an error.

### Pointing at a different bank

Any NextGenPSD2-conformant ASPSP works. Override in
`src/main/resources/application.properties`:

| Property | Meaning |
| --- | --- |
| `psd2.base-url` | Root of the ASPSP's XS2A API, without `/v1` |
| `psd2.client-id` | TPP id; blank means "read it from the certificate" |
| `psd2.redirect-uri` | Where the bank returns after the OAuth login |
| `psd2.consent-redirect-uri` | Where the bank returns after consent signing |
| `psd2.keystore` / `psd2.keystore-password` | PKCS#12 holding the QWAC |
| `psd2.transaction-history-days` | Size of the transaction window requested |

## Stock forecasting

The `/stock` page trains an LSTM (DL4J) over price history and FinBERT news
sentiment. It needs the sentiment service from `finbert.py` running on
`http://localhost:8001`.

## Tests

```
./gradlew test
```

## Contact

Mail me at hidrijouda@gmail.com
