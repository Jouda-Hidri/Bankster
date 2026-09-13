# Bankster [![Build Status](https://travis-ci.org/Jouda-Hidri/Bankster.svg?branch=master)](https://travis-ci.org/Jouda-Hidri/Bankster)

Bankster is a Java application covering two halves of the payments world:

* **Open banking** — an AISP client for the **Berlin Group NextGenPSD2 (XS2A)** API,
  which reads real accounts, balances and transactions from a bank.
* **A payments platform** — card acquiring, SEPA and cross-border transfers, a
  double-entry ledger, settlement and three-way reconciliation, with the risk and
  compliance controls that sit around them.

The second half is the larger one, and it is built to be *inspected* rather than just
run: every decision records why it was made, and the console at
`http://localhost:8099/payments` shows which rail a transfer took and why it was not
the one requested, what a payment's risk score was made of, how a settlement batch
decomposes into gross, fees and net, and which reconciliation breaks are open and how
old they are.

**Contents** — [Run it](#run-the-project) · [Connecting to a bank](#connecting-to-a-bank) ·
[Money and the ledger](#money-and-the-ledger--money-ledger) ·
[Card payments](#card-payments--cards) ·
[Routing and orchestration](#payment-routing-and-orchestration--orchestration) ·
[Banking rails](#banking-rails--rails) ·
[Smart routing](#smart-routing-when-instant-cannot-be-instant) ·
[Risk and compliance](#risk-and-compliance--risk) ·
[Settlement and reconciliation](#settlement-and-reconciliation--settlement-recon) ·
[Engineering primitives](#engineering-primitives--core) ·
[Configuration](#configuration) · [What is simulated](#what-is-simulated) ·
[Tests](#tests) · [Regression tests](#regression-tests)

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

Then go to http://localhost:8099/payments for the payments console, or
http://localhost:8099/psd2 to connect to a real bank.

A demo scenario runs at start-up and populates the console with a full lifecycle — an
exempted low-value payment, a challenged one, a decline, an acquirer failover, a split
shipment with a partial refund, a dispute defeated by 3-D Secure liability shift, a
card-testing burst, three transfers downgraded off the instant rail for three different
reasons, a blocked sanctions payment, a cross-border payment with FX, a settlement
cut-off, and a reconciliation that finds a deliberately short-paid line and holds the
payout. Set `payments.seed-demo-data=false` to skip it.

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

## The payments platform

Everything below lives under `src/main/java/bankster/client/payments/`. Each section
names the code and the one idea that drove its design.

### Money and the ledger — `Money`, `ledger/`

`Money` holds a `long` count of minor units and an ISO 4217 code. No amount anywhere in
the application is a `double`: binary floating point cannot represent 0.10, so repeated
addition drifts and a ledger that must balance to the cent stops balancing. Splitting is
done by `allocate`, which hands out the remainder a unit at a time so the parts always
sum back to the original.

The `Ledger` is append-only double-entry with four commitments:

| Commitment | Why |
| --- | --- |
| No update, no delete | A mistake is corrected by a reversing entry, so the wrong figure and its correction both stay visible |
| Every entry balances per currency | An unbalanced entry is rejected, not repaired |
| Balances are derived, never stored | A stored number can drift from the journal that is supposed to explain it |
| Each entry hashes its predecessor | A retroactive edit is detectable by `verifyIntegrity()` |

The single most important property in the whole codebase is in `PaymentBookkeeper`:
**authorization books nothing.** An authorization moves no money — it only reduces what
the cardholder may spend elsewhere — so there is no entry to make. Recording it as a
receivable overstates both assets and merchant liabilities by the value of every
authorization later voided or left to expire.

Capture is then booked as five postings rather than two, because five things are true at
once:

```
DR  scheme receivable        gross − interchange − scheme fees
DR  interchange expense      interchange
DR  scheme fees expense      scheme fees
  CR  merchant payable       gross − merchant discount
  CR  merchant discount rev. merchant discount
```

Both columns total the gross. The receivable is deliberately net of what the acquirer
deducts at source, so settlement later matches to the cent against what actually
arrives.

### Card payments — `cards/`

| Concept | Where | The point |
| --- | --- | --- |
| Authorization vs capture | `CardPaymentService`, `IssuerSimulator` | A hold is not a debit. Modelling them as one step is the commonest way a card integration ends up impossible to reconcile |
| Partial and multiple captures | `Capture`, `Payment` | Split shipments: money may only be taken for goods actually dispatched |
| Refunds | `Refund` | A fresh transaction in the opposite direction, not an undo. Fees are not returned |
| Voids | `CardPaymentService#voidAuthorization` | Always preferable to a refund where available — the cardholder never sees a debit |
| Chargebacks | `ChargebackService`, `Chargeback`, `ChargebackReason` | Full lifecycle: claw-back, representment, pre-arbitration, arbitration, with reason codes and deadlines |
| Soft vs hard declines | `DeclineCode` | A soft decline is worth retrying; a hard one is not, and re-attempting it attracts scheme fines |
| 3-D Secure | `ThreeDSecureService`, `ThreeDSecureResult` | Frictionless, challenge, attempted, and the ECI/CAVV values that actually carry the liability position |
| PSD2 SCA exemptions | `ScaExemption` | Low-value with cumulative counters, transaction risk analysis with earned ceilings, recurring, MIT, trusted beneficiary, corporate |
| Tokenization | `TokenVault`, `CardToken` | Merchant-scoped tokens, plus a payment account reference that links a card across merchants without storing a PAN |
| Acquiring vs issuing | `IssuerSimulator`, `AcquirerProcessor` | The two halves of the card business, and why interchange flows towards the issuer |
| Gateways, processors, acquirers | `AcquirerProcessor` javadoc | Three different things that one company often does, which is why the terms blur |
| Interchange caps | `BinTable`, `FeeSchedule` | 0.2% debit and 0.3% credit apply to EEA consumer cards only; commercial and non-EEA cards are uncapped and cost several times as much |

The PAN never leaves the vault. `Pan#toString` returns the masked form, so it cannot
reach a log line or an exception message, and nothing downstream of tokenization holds
one.

### Payment routing and orchestration — `orchestration/`

`PaymentRouter` filters processors to those that support the scheme and currency and are
healthy, then ranks by strategy — highest approval rate, lowest cost, or fastest
settlement. It returns an ordered chain rather than a single choice, and records why
every rejected processor was rejected: "why did my transaction go to the expensive
acquirer" has to be answerable after the fact.

Failover has a sharp constraint. **Only infrastructure failures may be retried
elsewhere.** If the issuer declined, the transaction has been decided, and sending it to
a second acquirer produces the same decline while counting as another attempt — which
the schemes monitor and fine for.

`CardPaymentService` is the orchestration layer, and the ordering is the substance: risk
runs before authentication because the score decides whether an exemption may be claimed;
authentication runs before routing because its result travels in the authorization;
nothing is booked until the issuer has answered.

### Banking rails — `rails/`

| Concept | Where |
| --- | --- |
| IBAN | `Iban` — mod-97 check digits, per-country lengths, construction from domestic details |
| BIC | `Bic` — institution/country/location/branch, and test-BIC detection |
| IBAN-only addressing | `IbanBicDirectory` — resolves the institution from the national bank code |
| SEPA Credit Transfer | `PaymentRail.SEPA_SCT`, cut-off and value dates via `Target2Calendar` |
| SEPA Instant | `PaymentRail.SEPA_INST` — seconds, 24/7, and irrevocable |
| **Smart rail routing** | `SepaRouter` — see below |
| TARGET2 | `PaymentRail.TARGET2` — same-day RTGS in central bank money |
| SWIFT | `SwiftService`, `CorrespondentNetwork` — nostro/vostro, multi-hop chains, fees deducted in transit |
| FX | `FxRates` — mid rate vs client rate, and the spread that is usually the largest cost |
| Payment lifecycle | `TransferStatus` — in ISO 20022 codes, where only `ACSC` means the money arrived |
| Rejection reasons | `RejectionReason` — `AM04` means retry when funded, `AC04` means never |
| Confirmation of Payee | `SepaPaymentService#confirmPayee` — mandatory under the Instant Payments Regulation |
| ISO 20022 | `Iso20022` — `pain.001`, `pacs.008`, `camt.053` generation and parsing |

#### Smart routing: when instant cannot be instant

The motivating case. A payer asks for an instant transfer and it is not possible, for one
of three reasons: the amount is over the institution's limit, the beneficiary's bank is
not reachable on the instant scheme, or the rail is down. The wrong behaviours are to
reject a payment that could perfectly well have gone by standard transfer, and to
silently downgrade it, leaving the payer believing money has arrived when it has not.

`SepaRouter` downgrades deliberately and records why, in this order:

1. **SEPA Instant** when reachable, within limit, and the rail is up.
2. **TARGET2** when the amount is too large for instant but the payment is still urgent —
   RTGS settles today and has no upper limit, which is exactly the gap it fills.
3. **SEPA Credit Transfer** as the dependable default, with the value date computed from
   the 15:00 CET cut-off and the TARGET calendar.
4. **SWIFT** when the payment leaves SEPA or the currency is not euro.

`RailDecision#customerFacingSummary` produces the sentence the payer is shown: *"Sending
by SEPA Credit Transfer instead of SEPA Instant Credit Transfer because the beneficiary's
bank (Nordea Sweden) is not reachable on SEPA Instant."*

On the amount limit: the EPC removed the scheme-level €100,000 ceiling in October 2025
under the Instant Payments Regulation, but institutions still apply their own
per-transaction limits for liquidity and fraud reasons. `payments.instant-transfer-limit`
is that institution-level limit, which is why it is configurable rather than a constant.

### Risk and compliance — `risk/`

Fraud and AML are different problems. Fraud asks whether this transaction will cost *us*
money — we are the victim. AML asks whether this money is clean and whether we are being
used to move it — we are the instrument, and the obligation is to detect and **report**
rather than merely to block.

`FraudEngine` is rule-based rather than a model, deliberately: every decision has to be
explainable to a disputing customer and to a regulator, the thresholds are what an
analyst actually tunes, and rules can be changed the morning an attack starts. Most of
the signal is in velocity rather than in any single transaction — forty €9 purchases
across thirty cards from one address in ten minutes is card testing, and that is
invisible to per-transaction checks. Cross-merchant velocity works on the payment account
reference, so it survives tokenization without anyone storing a PAN.

One subtlety worth naming: a **challenged** attempt is recorded too. An engine that only
records decided attempts cannot see an attack whose attempts all trip its own challenge
rule — the attack would suppress exactly the evidence it generates.

`AmlService` covers KYC gating, sanctions screening with fuzzy name matching tuned
towards false positives, and the classic typologies — structuring, pass-through, and
departure from the volume declared at onboarding — each producing a filed suspicious
activity report.

### Settlement and reconciliation — `settlement/`, `recon/`

Settlement runs on three clocks that are easy to conflate, and keeping them distinct is
what makes the ledger tie to a bank statement:

* the **cut-off** decides which transactions batch together;
* **funding** is when the acquirer's money reaches our account, one to three days later —
  until then the captures are a receivable, not cash;
* **payout** is when we pay the merchant, which may deliberately lag funding, because
  disputes can arrive after a merchant has been paid and disappeared.

A batch tracks two different nets. **Acquirer funding** is gross less what the acquirer
deducts at source; **merchant payout** is gross less what we charge the merchant. The
difference is the margin, and netting them hides the P&L.

`ReconciliationService` runs three legs, each catching something the others cannot:

1. **Internal ledger against the processor's report** — transactions recorded on one side
   only, and amounts or fees that differ.
2. **Processor report against the bank statement** — money reported as settled that did
   not arrive. The first leg is structurally blind to this, because both its inputs are
   claims rather than cash.
3. **The ledger against itself** — debits not equal to credits, or a journal whose hash
   chain no longer verifies. No external comparison can detect either.

Two practices stop it degenerating into a list nobody reads. Sub-cent differences are
matched *within tolerance* and counted rather than raised, because burying real breaks
among thousands of one-cent items is how real breaks get missed. And an unexplained
difference can be written off to a **suspense** account, which keeps the books balanced
without pretending the difference never happened — the suspense balance is itself a
reported figure somebody has to justify.

Breaks are classified and aged. The type decides who acts: an item present internally and
absent externally is usually timing; an item present externally and absent internally
means money moved with nothing on our books to explain it. A break open for a month is a
control failure, so severity rises with age. Anything at MEDIUM or above holds the
payout — a payout is irreversible, so the asymmetry favours holding.

### Engineering primitives — `core/`

| Concept | Where | The point |
| --- | --- | --- |
| Idempotency | `IdempotencyStore` | Replay, concurrent-duplicate and key-reuse conflict are three distinct cases; getting any wrong reintroduces the double charge |
| Exactly-once | `Outbox` | The wire is at-least-once; exactly-once *processing* is reconstructed at the consumer with a per-handler dedupe log |
| Eventual consistency | `Outbox` | The event is written beside the state change, so state and announcement can be temporarily out of step but never disagree |
| Failure recovery | `Outbox`, `Saga` | Bounded retries, then a dead letter that is visible rather than blocking the queue behind it |
| Distributed transactions | `Saga` | Compensation instead of rollback, because an acquirer has no notion of rolling back because our ledger write failed |
| Audit trails | `AuditTrail` | Hash-chained, because a dispute is argued on this evidence and application logs are mutable |
| Immutable history | `Ledger`, `JournalEntry` | Append-only with a hash chain; compensation is a new forward action, not an erasure — which is why the saga and the ledger agree |

The `Clock` is injected everywhere rather than calling `Instant.now()` in place. Almost
everything here is time-dependent — authorization expiry, cut-offs, representment
deadlines, velocity windows, break ageing — and none of it is testable against a real
clock. `TestClock` makes "an authorization lapses after seven days" a test that runs in a
millisecond.

### Configuration

Only the things that genuinely differ between institutions are configurable. Scheme rules
— interchange caps, the representment window, the low-value exemption ceiling — are not,
because they are imposed from outside and a deployment that sets them differently is
simply wrong.

| Property | Meaning |
| --- | --- |
| `payments.acquirer-fraud-rate-basis-points` | Measured fraud rate; sets the TRA exemption ceiling (13 bps → €100, 6 → €250, 1 → €500) |
| `payments.instant-transfer-limit` | This institution's per-transaction SEPA Instant limit |
| `payments.high-value-threshold` | Above this, an urgent transfer is routed to RTGS |
| `payments.demo-currency` | Currency the demo scenario operates in |
| `payments.seed-demo-data` | Whether to populate the console at start-up |

### What is simulated

Worth being explicit about, so the boundary is clear:

* **The issuer and the acquirers.** `IssuerSimulator` keeps real balances and applies real
  holds, and `SimulatedAcquirerProcessor` enforces over-capture and over-refund, but
  neither speaks to a card network. Reserved BINs are wired to fixed outcomes so declines,
  transient faults and stolen cards are demonstrable deterministically.
* **Rail settlement.** An instant transfer settles immediately and batch transfers settle
  when their value date arrives; no clearing mechanism is contacted.
* **The reference data.** The BIN table, the sanctions list, the reachability directory and
  the correspondent network hold enough entries to make the decisions demonstrable.
  Production versions are licensed datasets refreshed on a schedule.
* **Storage.** Everything is in memory. The ledger's invariants and posting model are
  identical whether the journal lives in a list or an append-only table; a durable
  implementation swaps the list for a database with a unique index on `sequence` and
  changes nothing else.

The open banking half is *not* simulated — it talks to a real bank sandbox over mutual
TLS with a real eIDAS-equivalent certificate.

## Stock forecasting

The `/stock` page trains an LSTM (DL4J) over price history and FinBERT news
sentiment. It needs the sentiment service from `finbert.py` running on
`http://localhost:8001`.

## Tests

```
./gradlew test

# Just the defect-pinned regression suite
./gradlew test --tests 'bankster.client.payments.regression.*'
```

No setup step. `Psd2Config` refuses to start without a TPP keystore and
`PaymentsWiringTest` boots the whole application context, so the build generates a
throwaway self-signed one into `build/test-certificates/` — self-signed, offline, trusted
by nothing, and regenerated if deleted. That is why the suite runs on a fresh clone
without first obtaining a sandbox certificate, and why nothing certificate-shaped is
committed. `src/test/resources/application.properties` points the tests at it; the real
sandbox certificate is still what `bootRun` uses.

Every run happens on GitHub Actions — Java 21, `./gradlew test`, with failures annotated
onto the diff. See `.github/workflows/build.yml`.

384 tests. The interesting ones are the failure paths rather than the happy ones:
over-capture and over-refund, idempotent replay and key-reuse conflict, saga compensation
including a compensation that itself fails, a dispute defeated by liability shift and the
reversal of the loss absorbed along the way, every kind of reconciliation break, and each
reason an instant transfer gets downgraded. `PaymentsWiringTest` starts the Spring context
and runs the whole scenario through it, asserting that the ledger still balances and its
hash chain still verifies afterwards — the property most likely to be broken by a change
somewhere else.

### Regression tests

`regression/PaymentRegressionTest` covers ten defects found while building this, organised
by defect rather than by feature. Each group names the mistake and how to reintroduce it,
so a failure says *which* mistake came back rather than just that something broke:

| # | Defect | Consequence |
| --- | --- | --- |
| 1 | A challenged attempt was not recorded with the fraud engine | Card testing was invisible: probes score into the challenge band, so every attempt was challenged, so nothing was recorded, so velocity never accumulated |
| 2 | Sanctions screening ran after Confirmation of Payee | A payment to a listed party was rejected as a name mismatch and the hit was never filed |
| 3 | Reconciliation keyed lines by payment and type | A split shipment's second capture was reported as a duplicate presentment |
| 4 | Amount mismatches rated LOW on the day they were found | A short-paid batch was paid out before anyone looked at it |
| 5 | `SwiftService#send` booked nothing | The payer's balance was never debited for a cross-border payment |
| 6 | A won dispute credited the headline disputed amount | Left a phantom chargeback-loss expense and the merchant better off than before the dispute |
| 7 | The value date ignored the cut-off | A transfer instructed at 16:00 was given D+1 instead of D+2 |
| 8 | `SwiftService` had no funds check | A safeguarded-funds balance could go negative |
| 9 | `recordSettlementReceipt` assumed a positive net | A batch whose refunds exceeded its sales could not be booked at all |
| 10 | `hasCompleted` ignored the retention window | The query contradicted the `execute` it was querying |

Each was verified by reverting its fix and confirming the matching test fails — the only
thing that distinguishes a regression test from a test that happens to pass. Bug 1's group
needed two rounds of rewriting before it caught its own defect, which is worth recording
because both failures are easy to repeat:

* The first version drove probes that were *approved* rather than challenged — the fixture
  gave them a matching geography, so they scored 10 and sailed through. History
  accumulated via the approval path and the test passed with the fix removed. It now
  asserts directly that issuing a challenge registers the attempt, and the behavioural
  version supplies the geography mismatch needed to reach the challenge band at all.
* The fix has two halves — the orchestrator recording the attempt, and the engine counting
  it — and the card-testing rule walks the history directly while the per-card and
  decline-probing rules go through a windowed helper. Breaking either half alone left the
  other test green, so both routes are asserted.

Bugs 1–6 were found by the start-up demo scenario producing a number that could not be
right. Bugs 8, 9 and 10 were found by a test written for something else failing for an
unrelated reason, and bug 7 by working through the cut-off arithmetic while choosing dates
for a test. None were found by reading the code, which is the argument for keeping the demo
in the build.

## Contact

Mail me at hidrijouda@gmail.com
