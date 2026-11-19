package io.cloudscale;

import com.fasterxml.jackson.databind.JsonNode;
import io.cloudscale.JobContext.JobStoppedException;
import io.cloudscale.JobProcessors.JobProcessor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Background worker. Polls the queue, claims at most (concurrency - active) jobs so memory stays bounded, and runs them
 * on a fixed thread pool. Run any number of worker instances; SKIP LOCKED keeps them from claiming the same job.
 * Delivery is at-least-once: a worker crash lets the lease expire and another worker re-runs the job.
 */
@Component
@ConditionalOnProperty(name = "cloudscale.jobs.worker-enabled", havingValue = "true")
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository repo;
    private final Map<String, JobProcessor> processors;
    private final Props props;
    private final MeterRegistry registry;
    private final io.micrometer.tracing.Tracer tracer;
    private final AtomicInteger active = new AtomicInteger();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "job-poller"));
    private final ThreadPoolExecutor pool;
    private volatile boolean running = true;

    public JobWorker(JobRepository repo, List<JobProcessor> processors, Props props, MeterRegistry registry,
                     org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer) {
        this.tracer = tracer.getIfAvailable();
        this.repo = repo;
        this.processors = processors.stream().collect(Collectors.toMap(JobProcessor::type, Function.identity()));
        this.props = props;
        this.registry = registry;
        AtomicInteger n = new AtomicInteger();
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(props.jobs().concurrency(), r -> new Thread(r, "job-worker-" + n.incrementAndGet()));
        Gauge.builder("cloudscale.jobs.active", active, AtomicInteger::get).description("Jobs currently executing on this worker").register(registry);
    }

    @PostConstruct
    void start() {
        poller.scheduleWithFixedDelay(this::poll, 0, props.jobs().pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        log.info("Job worker started (concurrency={}, lease={})", props.jobs().concurrency(), props.jobs().lease());
    }

    /** Graceful shutdown: stop claiming, give in-flight jobs time to finish; leftovers are reclaimed after lease expiry. */
    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        poller.shutdown();
        pool.shutdown();
        if (!pool.awaitTermination(props.jobs().shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("{} job(s) still running at shutdown; they will be reclaimed after their lease expires", active.get());
            pool.shutdownNow();
        }
    }

    private void poll() {
        if (!running) {
            return;
        }
        try {
            repo.sweep();
            int free = props.jobs().concurrency() - active.get();
            if (free <= 0) {
                return;
            }
            for (Job j : repo.claim(free, props.jobs().lease().toMillis() / 1000.0)) {
                active.incrementAndGet();
                try {
                    pool.execute(() -> run(j));
                } catch (RuntimeException e) {
                    active.decrementAndGet();   // pool is shutting down; the lease will expire and the job be reclaimed
                }
            }
        } catch (Exception e) {
            log.warn("Job poll failed (database unavailable?); will retry: {}", e.toString());
        }
    }

    static Duration backoff(int attempt, Duration base) {
        long seconds = base.toSeconds() << Math.min(Math.max(attempt - 1, 0), 20);
        return Duration.ofSeconds(Math.min(seconds, 300));
    }

    private void run(Job job) {
        if (tracer == null) {
            execute(job);
            return;
        }
        io.micrometer.tracing.Span span = startSpan(job);
        try (io.micrometer.tracing.Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            execute(job);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** Child of the submitting request's span when a traceparent was stored with the job; a new trace otherwise. */
    private io.micrometer.tracing.Span startSpan(Job job) {
        io.micrometer.tracing.Span.Builder b = tracer.spanBuilder().name("job.process")
                .tag("job.id", job.id().toString()).tag("job.type", job.type()).tag("job.attempt", Integer.toString(job.attemptCount()));
        String[] p = job.traceparent() == null ? new String[0] : job.traceparent().split("-");
        if (p.length == 4 && p[1].length() == 32 && p[2].length() == 16) {
            b.setParent(tracer.traceContextBuilder().traceId(p[1]).spanId(p[2]).sampled("01".equals(p[3])).build());
        }
        return b.start();
    }

    private void execute(Job job) {
        Timer.Sample sample = Timer.start(registry);
        long t0 = System.nanoTime();
        String outcome = "failed";
        JobContext ctx = new JobContext(repo, job, props.jobs().lease().toMillis() / 1000.0);
        try {
            JobProcessor p = processors.get(job.type());
            if (p == null) {
                throw new PermanentJobException("unknown job type");
            }
            simulate(job, ctx);
            JsonNode result = p.process(job, ctx);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (repo.complete(job.id(), job.attemptCount(), result, ms)) {
                outcome = "succeeded";
            } else {
                outcome = "discarded";
                log.warn("Job {} finished but its lease was lost; result discarded (another worker owns it)", job.id());
            }
        } catch (JobStoppedException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ctx.leaseLost() || !ctx.cancelled() && Thread.currentThread().isInterrupted()) {
                outcome = "abandoned";
            } else if (repo.markCancelled(job.id(), job.attemptCount())) {
                outcome = "cancelled";
            }
            log.info("Job {} stopped: {} ({} ms)", job.id(), e.getMessage(), ms);
        } catch (PermanentJobException e) {
            repo.fail(job.id(), job.attemptCount(), e.getMessage(), (System.nanoTime() - t0) / 1_000_000);
            log.info("Job {} failed permanently: {}", job.id(), e.getMessage());
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (job.attemptCount() >= job.maxAttempts()) {
                repo.fail(job.id(), job.attemptCount(), "Gave up after " + job.attemptCount() + " attempts: " + e.getMessage(), ms);
                log.warn("Job {} failed after {} attempts: {}", job.id(), job.attemptCount(), e.toString());
            } else {
                Duration delay = backoff(job.attemptCount(), props.jobs().backoffBase());
                repo.retry(job.id(), job.attemptCount(), "Attempt " + job.attemptCount() + " failed: " + e.getMessage(), delay.toMillis() / 1000.0);
                outcome = "retried";
                log.warn("Job {} attempt {} failed, retrying in {}s: {}", job.id(), job.attemptCount(), delay.toSeconds(), e.toString());
            }
        } finally {
            registry.counter("cloudscale.jobs.finished", "type", job.type(), "outcome", outcome).increment();
            sample.stop(registry.timer("cloudscale.jobs.duration", "type", job.type()));
            active.decrementAndGet();
        }
    }

    /** Failure-injection hook for tests; inert unless cloudscale.jobs.test-hooks=true. */
    private void simulate(Job job, JobContext ctx) throws InterruptedException {
        if (!props.jobs().testHooks()) {
            return;
        }
        JsonNode sim = job.payload().path("simulate");
        if (!sim.isObject()) {
            return;
        }
        long sleep = sim.path("sleepMs").asLong(0);
        for (long slept = 0; slept < sleep; slept += 100) {
            ctx.checkpoint();
            Thread.sleep(100);
        }
        if (job.attemptCount() <= sim.path("failAttempts").asInt(0)) {
            throw new IllegalStateException("simulated transient failure");
        }
    }
}
