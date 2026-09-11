package bankster.client.psd2;

import java.util.stream.Collectors;

import org.springframework.web.reactive.function.client.ClientResponse;

import bankster.client.psd2.Psd2Dtos.ErrorResponse;
import reactor.core.publisher.Mono;

/** Turns an XS2A error payload into a {@link Psd2Exception} carrying the ASPSP's message. */
final class Psd2Errors {

    private Psd2Errors() {
    }

    static Mono<Throwable> toException(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(ErrorResponse.class)
                .map(error -> describe(status, error))
                // A non-XS2A body (HTML error page, empty response) must not mask the status.
                .onErrorResume(ignored -> Mono.just(new Psd2Exception(status, "XS2A call failed with HTTP " + status)))
                .defaultIfEmpty(new Psd2Exception(status, "XS2A call failed with HTTP " + status))
                .map(throwable -> throwable);
    }

    private static Throwable describe(int status, ErrorResponse error) {
        if (error == null || error.tppMessages() == null || error.tppMessages().isEmpty()) {
            return new Psd2Exception(status, "XS2A call failed with HTTP " + status);
        }
        String detail = error.tppMessages().stream()
                .map(message -> message.code() + ": " + message.text())
                .collect(Collectors.joining("; "));
        return new Psd2Exception(status, detail);
    }
}
