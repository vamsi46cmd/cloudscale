package io.cloudscale;

/**
 * Handed to a running job. {@link #checkpoint()} periodically extends the lease (heartbeat) and notices cancellation or
 * a lost lease; long-running processors must call it regularly.
 */
public class JobContext {
    public static class JobStoppedException extends RuntimeException {
        public JobStoppedException(String m) {
            super(m);
        }
    }

    private final JobRepository repo;
    private final Job job;
    private final double leaseSeconds;
    private long lastBeat = System.nanoTime();
    private volatile boolean cancelled;
    private volatile boolean leaseLost;

    public JobContext(JobRepository repo, Job job, double leaseSeconds) {
        this.repo = repo;
        this.job = job;
        this.leaseSeconds = leaseSeconds;
    }

    /** Throws JobStoppedException if the job was cancelled or this worker no longer holds the lease. */
    public void checkpoint() {
        long now = System.nanoTime();
        if ((now - lastBeat) / 1e9 >= leaseSeconds / 3) {
            lastBeat = now;
            if (!repo.renewLease(job.id(), job.attemptCount(), leaseSeconds)) {
                leaseLost = true;
                throw new JobStoppedException("lease lost");
            }
            if (repo.isCancelRequested(job.id())) {
                cancelled = true;
                throw new JobStoppedException("cancelled");
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new JobStoppedException("worker shutting down");
        }
    }

    public boolean leaseLost() {
        return leaseLost;
    }

    public boolean cancelled() {
        return cancelled;
    }
}
