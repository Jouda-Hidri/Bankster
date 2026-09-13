package bankster.client.payments.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import bankster.client.payments.Money;
import bankster.client.payments.cards.CardPaymentService.PaymentResult;
import bankster.client.payments.ledger.ChartOfAccounts;

/** The card lifecycle, end to end, including the paths that are supposed to fail. */
class CardPaymentServiceTest {

    /** Approves, and the issuer participates in 3-D Secure. */
    private static final String GOOD_BIN = "400000";
    /** Scripted to decline for insufficient funds. */
    private static final String NSF_BIN = "400002";
    /** Scripted to return a retryable issuer-unavailable. */
    private static final String UNAVAILABLE_BIN = "400005";
    /** A commercial card — uncapped interchange. */
    private static final String COMMERCIAL_BIN = "492910";

    private CardFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CardFixture();
    }

    // --- Authorization ----------------------------------------------------

    @Test
    void anApprovedAuthorizationHoldsFundsWithoutBookingAnything() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");

        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "25.00"));

        assertTrue(result.approved(), result.message());
        assertEquals(PaymentStatus.AUTHORIZED, result.payment().status());
        assertEquals(fixture.eur("25.00"), result.payment().authorizedAmount());
        assertEquals(fixture.eur("0.00"), result.payment().capturedAmount());

        // The key accounting property: an authorization moves no money.
        assertEquals(0, fixture.ledger.size(),
                "booking an authorization would overstate assets and merchant liabilities");

        // It does reduce what the cardholder can spend elsewhere.
        String par = result.payment().card().par();
        assertEquals(fixture.eur("25.00"), fixture.issuer.account(par).orElseThrow().heldAmount());
        assertEquals(fixture.eur("975.00"),
                fixture.issuer.account(par).orElseThrow().availableToSpend());
    }

    @Test
    void anAuthorizationOverTheCardholdersLimitIsDeclined() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "20.00");

        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "100.00"));

        assertFalse(result.approved());
        assertEquals(PaymentStatus.AUTHORIZATION_DECLINED, result.payment().status());
        assertEquals(DeclineCode.INSUFFICIENT_FUNDS, result.payment().declineCode());
        assertTrue(result.payment().declineCode().isSoftDecline(), "a retry once funded could work");
    }

    @Test
    void aScriptedDeclineIsNotRetriedAgainstAnotherProcessor() {
        CardDetails card = fixture.cardOn(NSF_BIN, "1000.00");

        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "10.00"));

        assertFalse(result.approved());
        assertEquals(1, result.processorsAttempted().size(),
                "an issuer decision is not re-sent; the schemes fine excessive re-attempts");
    }

    @Test
    void aTechnicalFailureFailsOverToTheNextProcessor() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        fixture.primary.failNextAuthorizations(1);

        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "30.00"));

        assertTrue(result.approved(), result.message());
        assertEquals(List.of("primary", "secondary"), result.processorsAttempted());
        assertEquals("secondary", result.payment().processorId());
    }

    @Test
    void anUnhealthyProcessorIsNotEvenAttempted() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        fixture.primary.setHealthy(false);

        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "30.00"));

        assertTrue(result.approved());
        assertEquals(List.of("secondary"), result.processorsAttempted(),
                "filtering before dialling saves a second of checkout latency");
    }

    @Test
    void withNoHealthyProcessorThePaymentFailsRatherThanHanging() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        fixture.primary.setHealthy(false);
        fixture.secondary.setHealthy(false);

        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "30.00"));

        assertFalse(result.approved());
        assertEquals(DeclineCode.ISSUER_UNAVAILABLE, result.payment().declineCode());
    }

    @Test
    void anIssuerUnavailableIsRetriedElsewhereAndStillFailsIfBothSeeIt() {
        // Both processors go to the same issuer, so a scripted issuer-level outage is
        // seen by both — but it is retryable, so both are tried.
        CardDetails card = fixture.cardOn(UNAVAILABLE_BIN, "1000.00");

        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "10.00"));

        assertFalse(result.approved());
        assertEquals(List.of("primary", "secondary"), result.processorsAttempted());
        assertTrue(result.payment().declineCode().isTechnicalFailure());
    }

    @Test
    void anUnknownCardIsDeclinedWithoutAnIssuerAccount() {
        CardDetails card = fixture.cardOn(GOOD_BIN);

        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "10.00"));

        assertFalse(result.approved());
        assertEquals(DeclineCode.INVALID_CARD_NUMBER, result.payment().declineCode());
        assertFalse(result.payment().declineCode().isSoftDecline());
    }

    // --- Capture ----------------------------------------------------------

    @Test
    void captureBooksFiveWaysAndBothColumnsTotalTheGross() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "100.00"));

        fixture.service.captureAll(authorized.payment().paymentId(), fixture.key());

        assertEquals(PaymentStatus.CAPTURED, authorized.payment().status());
        assertEquals(1, fixture.ledger.size());
        assertTrue(fixture.ledger.trialBalance().balances());

        // 0.9% + 0.05 merchant discount, 0.2% interchange, 0.02 scheme fee.
        // The merchant is owed 100.00 less the 0.95 discount.
        assertEquals(fixture.eur("99.05"), fixture.merchantBalance());
        assertEquals(fixture.eur("0.95"), fixture.balanceOf(ChartOfAccounts.REVENUE_MERCHANT_DISCOUNT));
        assertEquals(fixture.eur("0.20"), fixture.balanceOf(ChartOfAccounts.EXPENSE_INTERCHANGE));
        assertEquals(fixture.eur("0.02"), fixture.balanceOf(ChartOfAccounts.EXPENSE_SCHEME_FEES));
        // The receivable is net of what the acquirer deducts at source.
        assertEquals(fixture.eur("99.78"), fixture.balanceOf(ChartOfAccounts.SCHEME_RECEIVABLE));

        // Nothing is in the bank yet — that only happens at settlement.
        assertEquals(fixture.eur("0.00"), fixture.balanceOf(ChartOfAccounts.BANK_OPERATING));
    }

    @Test
    void partialCapturesAccumulateAndLeaveThePaymentPartiallyCaptured() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "100.00"));
        String paymentId = authorized.payment().paymentId();

        fixture.service.capture(paymentId, fixture.eur("40.00"), fixture.key());
        assertEquals(PaymentStatus.PARTIALLY_CAPTURED, authorized.payment().status());

        fixture.service.capture(paymentId, fixture.eur("60.00"), fixture.key());
        assertEquals(PaymentStatus.CAPTURED, authorized.payment().status());

        assertEquals(fixture.eur("100.00"), authorized.payment().capturedAmount());
        assertEquals(fixture.eur("0.00"), authorized.payment().uncapturedAmount());
        assertEquals(2, authorized.payment().captures().size());
        assertTrue(fixture.ledger.trialBalance().balances());
    }

    @Test
    void captureCannotExceedWhatWasAuthorized() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "50.00"));

        PaymentResult tooMuch = fixture.service.capture(
                authorized.payment().paymentId(), fixture.eur("60.00"), fixture.key());

        assertFalse(tooMuch.approved());
        assertTrue(tooMuch.message().contains("exceeds the uncaptured"), tooMuch.message());
        assertEquals(0, fixture.ledger.size(), "a refused capture books nothing");
    }

    @Test
    void captureIsRejectedOnAPaymentThatWasNeverAuthorized() {
        CardDetails card = fixture.cardOn(NSF_BIN, "1000.00");
        PaymentResult declined = fixture.service.authorize(fixture.authOnly(card, "10.00"));

        PaymentResult capture = fixture.service.capture(
                declined.payment().paymentId(), fixture.eur("10.00"), fixture.key());

        assertFalse(capture.approved());
        assertTrue(capture.message().contains("cannot capture"), capture.message());
    }

    @Test
    void capturingTwiceUnderTheSameKeyReplaysRatherThanChargesAgain() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "100.00"));
        String paymentId = authorized.payment().paymentId();
        String sharedKey = fixture.key();

        fixture.service.capture(paymentId, fixture.eur("40.00"), sharedKey);
        PaymentResult replay = fixture.service.capture(paymentId, fixture.eur("40.00"), sharedKey);

        assertTrue(replay.replayed());
        assertEquals(fixture.eur("40.00"), authorized.payment().capturedAmount());
        assertEquals(1, authorized.payment().captures().size());
        assertEquals(1, fixture.ledger.size(), "the money is claimed once");
    }

    @Test
    void autoCaptureAuthorizesAndCapturesInOneCall() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");

        PaymentResult result = fixture.service.authorize(fixture.authAndCapture(card, "18.50"));

        assertTrue(result.approved());
        assertEquals(PaymentStatus.CAPTURED, result.payment().status());
        assertEquals(fixture.eur("18.50"), result.payment().capturedAmount());
    }

    // --- Void -------------------------------------------------------------

    @Test
    void voidingReleasesTheHoldAndBooksNothing() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "75.00"));
        String par = authorized.payment().card().par();

        PaymentResult voided = fixture.service.voidAuthorization(
                authorized.payment().paymentId(), fixture.key());

        assertTrue(voided.approved());
        assertEquals(PaymentStatus.VOIDED, authorized.payment().status());
        assertEquals(fixture.eur("0.00"), fixture.issuer.account(par).orElseThrow().heldAmount());
        assertEquals(fixture.eur("1000.00"),
                fixture.issuer.account(par).orElseThrow().availableToSpend());
        assertEquals(0, fixture.ledger.size(),
                "nothing was booked at authorization, so there is nothing to reverse");
    }

    @Test
    void aCapturedPaymentCannotBeVoided() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult result = fixture.service.authorize(fixture.authAndCapture(card, "30.00"));

        PaymentResult attempt = fixture.service.voidAuthorization(
                result.payment().paymentId(), fixture.key());

        assertFalse(attempt.approved());
        assertTrue(attempt.message().contains("uncaptured authorization"), attempt.message());
    }

    // --- Refund -----------------------------------------------------------

    @Test
    void refundingChargesTheMerchantAndDoesNotReturnTheFees() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult result = fixture.service.authorize(fixture.authAndCapture(card, "100.00"));
        String paymentId = result.payment().paymentId();
        Money afterCapture = fixture.merchantBalance();

        fixture.service.refund(paymentId, fixture.eur("40.00"), "returned", fixture.key());

        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.payment().status());
        assertEquals(afterCapture.minus(fixture.eur("40.00")), fixture.merchantBalance());
        // The discount earned on the original sale is not given back.
        assertEquals(fixture.eur("0.95"),
                fixture.balanceOf(ChartOfAccounts.REVENUE_MERCHANT_DISCOUNT));
        assertTrue(fixture.ledger.trialBalance().balances());
    }

    @Test
    void refundingEverythingCapturedMarksThePaymentRefunded() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult result = fixture.service.authorize(fixture.authAndCapture(card, "60.00"));

        fixture.service.refund(result.payment().paymentId(), fixture.eur("60.00"),
                "cancelled", fixture.key());

        assertEquals(PaymentStatus.REFUNDED, result.payment().status());
        assertTrue(result.payment().isFullyRefunded());
        assertEquals(fixture.eur("0.00"), result.payment().refundableAmount());
    }

    @Test
    void refundCannotExceedWhatWasCaptured() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult result = fixture.service.authorize(fixture.authAndCapture(card, "50.00"));

        PaymentResult tooMuch = fixture.service.refund(result.payment().paymentId(),
                fixture.eur("80.00"), "overclaim", fixture.key());

        assertFalse(tooMuch.approved());
        assertTrue(tooMuch.message().contains("exceeds the refundable"), tooMuch.message());
    }

    @Test
    void anUncapturedPaymentCannotBeRefunded() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "50.00"));

        PaymentResult refund = fixture.service.refund(authorized.payment().paymentId(),
                fixture.eur("10.00"), "nothing to return", fixture.key());

        assertFalse(refund.approved());
        assertTrue(refund.message().contains("nothing has been captured"), refund.message());
    }

    // --- Idempotency on authorization ------------------------------------

    @Test
    void retryingAnAuthorizationUnderTheSameKeyDoesNotChargeTwice() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        CardPaymentService.AuthorizeCommand command = fixture.authAndCapture(card, "45.00");

        PaymentResult first = fixture.service.authorize(command);
        PaymentResult second = fixture.service.authorize(command);

        assertTrue(second.replayed());
        assertEquals(first.payment().paymentId(), second.payment().paymentId());
        assertEquals(1, fixture.payments.size());
        assertEquals(1, fixture.ledger.size());
    }

    @Test
    void reusingAKeyForADifferentAmountIsAConflict() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        String sharedKey = "shared";

        fixture.service.authorize(new CardPaymentService.AuthorizeCommand(
                CardFixture.MERCHANT, "order-1", card, fixture.eur("10.00"), "5411",
                false, false, false, false, false, "127.0.0.1", "EE", "EE", sharedKey, true));

        assertThrows(bankster.client.payments.core.IdempotencyStore.IdempotencyConflictException.class,
                () -> fixture.service.authorize(new CardPaymentService.AuthorizeCommand(
                        CardFixture.MERCHANT, "order-1", card, fixture.eur("500.00"), "5411",
                        false, false, false, false, false, "127.0.0.1", "EE", "EE", sharedKey, true)));
    }

    // --- Expiry -----------------------------------------------------------

    @Test
    void anAuthorizationLeftTooLongExpiresAndCannotThenBeCaptured() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "50.00"));
        String paymentId = authorized.payment().paymentId();

        fixture.clock.advance(IssuerSimulator.HOLD_LIFETIME.plus(Duration.ofHours(1)));

        assertEquals(1, fixture.service.expireStaleAuthorizations());
        assertEquals(PaymentStatus.EXPIRED, authorized.payment().status());

        PaymentResult capture = fixture.service.capture(paymentId, fixture.eur("50.00"), fixture.key());
        assertFalse(capture.approved());
    }

    @Test
    void theIssuerReleasesTheHoldWhenAnAuthorizationExpires() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult authorized = fixture.service.authorize(fixture.authOnly(card, "50.00"));
        String par = authorized.payment().card().par();

        fixture.clock.advance(IssuerSimulator.HOLD_LIFETIME.plus(Duration.ofHours(1)));
        fixture.service.expireStaleAuthorizations();

        assertEquals(fixture.eur("0.00"), fixture.issuer.account(par).orElseThrow().heldAmount());
    }

    // --- Pricing ----------------------------------------------------------

    @Test
    void aCommercialCardIsPricedOutsideTheInterchangeCaps() {
        CardDetails consumer = fixture.cardOn(GOOD_BIN, "1000.00");
        CardDetails commercial = fixture.cardOn(COMMERCIAL_BIN, "1000.00");

        fixture.service.authorize(fixture.authAndCapture(consumer, "100.00"));
        Money consumerInterchange = fixture.balanceOf(ChartOfAccounts.EXPENSE_INTERCHANGE);

        fixture.service.authorize(fixture.authAndCapture(commercial, "100.00"));
        Money totalInterchange = fixture.balanceOf(ChartOfAccounts.EXPENSE_INTERCHANGE);
        Money commercialInterchange = totalInterchange.minus(consumerInterchange);

        assertEquals(fixture.eur("0.20"), consumerInterchange, "0.2% capped consumer debit");
        assertEquals(fixture.eur("1.50"), commercialInterchange, "1.5% uncapped commercial");
        assertTrue(commercialInterchange.isGreaterThan(consumerInterchange));
    }

    // --- Evidence ---------------------------------------------------------

    @Test
    void everyDecisionIsRecordedInTheAuditTrail() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult result = fixture.service.authorize(fixture.authAndCapture(card, "20.00"));
        String paymentId = result.payment().paymentId();

        List<String> actions = fixture.auditTrail.forSubject(paymentId).stream()
                .map(bankster.client.payments.core.AuditTrail.AuditEvent::action)
                .toList();

        assertTrue(actions.contains("payment.created"));
        assertTrue(actions.contains("payment.risk_scored"));
        assertTrue(actions.contains("payment.authenticated"));
        assertTrue(actions.contains("payment.routed"));
        assertTrue(actions.contains("payment.authorized"));
        assertTrue(actions.contains("payment.captured"));
        assertTrue(fixture.auditTrail.verifyIntegrity());
    }

    @Test
    void outcomesArePublishedForTheRestOfTheSystem() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        fixture.service.authorize(fixture.authAndCapture(card, "20.00"));

        assertEquals(1, fixture.outbox.eventsOfType("payment.authorized").size());
        assertEquals(1, fixture.outbox.eventsOfType("payment.captured").size());
    }

    @Test
    void theLifecycleHistoryExplainsHowThePaymentReachedItsState() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult result = fixture.service.authorize(fixture.authAndCapture(card, "20.00"));

        List<PaymentStatus> states = result.payment().history().stream()
                .map(Payment.StatusTransition::to)
                .toList();

        assertEquals(List.of(PaymentStatus.CREATED, PaymentStatus.AUTHENTICATED,
                PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED), states);
        assertTrue(result.payment().history().stream()
                .allMatch(transition -> transition.reason() != null && !transition.reason().isBlank()));
    }

    @Test
    void anIllegalTransitionIsRefusedRatherThanSilentlyApplied() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult result = fixture.service.authorize(fixture.authAndCapture(card, "20.00"));
        Payment payment = result.payment();

        assertThrows(IllegalStateException.class, () ->
                payment.transitionTo(PaymentStatus.AUTHORIZED, "backwards", fixture.clock.instant()));
    }

    @Test
    void paymentsCanBeFoundByMerchantAndStatus() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        fixture.service.authorize(fixture.authAndCapture(card, "20.00"));
        fixture.service.authorize(fixture.authOnly(card, "30.00"));

        assertEquals(2, fixture.payments.forMerchant(CardFixture.MERCHANT).size());
        assertEquals(1, fixture.payments.withStatus(PaymentStatus.CAPTURED).size());
        assertEquals(1, fixture.payments.awaitingCapture().size());
        assertTrue(fixture.payments.forMerchant("nobody").isEmpty());
    }

    @Test
    void thePanNeverReachesThePaymentRecord() {
        CardDetails card = fixture.cardOn(GOOD_BIN, "1000.00");
        PaymentResult result = fixture.service.authorize(fixture.authOnly(card, "20.00"));

        String token = result.payment().card().token();
        assertNotEquals(card.pan().value(), token);
        assertFalse(result.payment().card().masked().contains(card.pan().value()));
    }
}
