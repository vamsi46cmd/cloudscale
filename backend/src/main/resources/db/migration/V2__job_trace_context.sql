-- W3C traceparent of the request that submitted the job, so the worker's span joins the same trace.
ALTER TABLE processing_jobs ADD COLUMN traceparent varchar(100);
