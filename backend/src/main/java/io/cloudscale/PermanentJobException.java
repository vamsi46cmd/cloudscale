package io.cloudscale;

/** Failure that retrying cannot fix (malformed payload, missing data): the job goes straight to FAILED. */
public class PermanentJobException extends RuntimeException {
    public PermanentJobException(String message) {
        super(message);
    }
}
