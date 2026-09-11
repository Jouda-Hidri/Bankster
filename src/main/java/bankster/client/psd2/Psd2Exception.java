package bankster.client.psd2;

/** Raised when the ASPSP rejects an XS2A call, carrying the TPP message it returned. */
public class Psd2Exception extends RuntimeException {

    private final int status;

    public Psd2Exception(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
