package bankster.client.payments.orchestration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import bankster.client.payments.cards.Payment;
import bankster.client.payments.cards.PaymentStatus;

/**
 * Where payments live.
 *
 * <p>In-memory, because this application has no database by design. The access
 * patterns are the ones a real repository would be indexed for: by id for every
 * operation, by merchant for reporting, and by status for the sweeps that settle
 * captures and expire stale authorizations.
 */
@Component
public class PaymentRepository {

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    public Payment save(Payment payment) {
        payments.put(payment.paymentId(), payment);
        return payment;
    }

    public Optional<Payment> find(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }

    public Payment require(String paymentId) {
        return find(paymentId).orElseThrow(
                () -> new IllegalArgumentException("Unknown payment: " + paymentId));
    }

    public List<Payment> all() {
        return payments.values().stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    public List<Payment> forMerchant(String merchantId) {
        return all().stream().filter(payment -> payment.merchantId().equals(merchantId)).toList();
    }

    public List<Payment> withStatus(PaymentStatus status) {
        return all().stream().filter(payment -> payment.status() == status).toList();
    }

    /** Authorized, not yet captured — the set the expiry sweep walks. */
    public List<Payment> awaitingCapture() {
        return all().stream()
                .filter(payment -> payment.status() == PaymentStatus.AUTHORIZED
                        || payment.status() == PaymentStatus.PARTIALLY_CAPTURED)
                .toList();
    }

    public int size() {
        return payments.size();
    }
}
