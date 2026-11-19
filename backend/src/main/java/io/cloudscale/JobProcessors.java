package io.cloudscale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

public final class JobProcessors {
    private JobProcessors() {}

    public interface JobProcessor {
        String type();

        JsonNode process(Job job, JobContext ctx) throws Exception;
    }

    public static final int MAX_CSV_ROWS = 100_000;
    public static final int MAX_COLUMNS = 10;

    /** count / sum / mean / min / max of selected numeric columns. Decimal arithmetic is exact (BigDecimal). */
    @Component
    public static class CsvAnalytics implements JobProcessor {
        private final ObjectMapper mapper;

        public CsvAnalytics(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override public String type() { return "CSV_ANALYTICS"; }

        @Override
        public JsonNode process(Job job, JobContext ctx) {
            String csv = job.payload().path("csv").asText("");
            List<String> wanted = new ArrayList<>();
            job.payload().path("columns").forEach(c -> wanted.add(c.asText()));
            if (csv.isBlank() || wanted.isEmpty() || wanted.size() > MAX_COLUMNS) {
                throw new PermanentJobException("payload needs a csv and 1-" + MAX_COLUMNS + " columns");
            }
            List<String[]> rows = Csv.parse(csv, MAX_CSV_ROWS);
            if (rows.isEmpty()) {
                throw new PermanentJobException("CSV is empty");
            }
            String[] header = rows.get(0);
            int[] idx = new int[wanted.size()];
            for (int c = 0; c < idx.length; c++) {
                idx[c] = -1;
                for (int h = 0; h < header.length; h++) {
                    if (header[h].trim().equals(wanted.get(c))) {
                        idx[c] = h;
                        break;
                    }
                }
                if (idx[c] < 0) {
                    throw new PermanentJobException("column '" + wanted.get(c) + "' not found in CSV header");
                }
            }
            long[] count = new long[idx.length];
            long[] missing = new long[idx.length];
            BigDecimal[] sum = new BigDecimal[idx.length];
            BigDecimal[] min = new BigDecimal[idx.length];
            BigDecimal[] max = new BigDecimal[idx.length];
            for (int c = 0; c < idx.length; c++) {
                sum[c] = BigDecimal.ZERO;
            }
            for (int r = 1; r < rows.size(); r++) {
                if (r % 1000 == 0) {
                    ctx.checkpoint();
                }
                String[] row = rows.get(r);
                for (int c = 0; c < idx.length; c++) {
                    String cell = idx[c] < row.length ? row[idx[c]].trim() : "";
                    if (cell.isEmpty()) {
                        missing[c]++;
                        continue;
                    }
                    BigDecimal v;
                    try {
                        v = new BigDecimal(cell);
                    } catch (NumberFormatException e) {
                        // The offending value is deliberately not echoed (it may be customer data).
                        throw new PermanentJobException("row " + (r + 1) + ", column '" + wanted.get(c) + "' is not numeric");
                    }
                    count[c]++;
                    sum[c] = sum[c].add(v);
                    min[c] = min[c] == null || v.compareTo(min[c]) < 0 ? v : min[c];
                    max[c] = max[c] == null || v.compareTo(max[c]) > 0 ? v : max[c];
                }
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("rows", rows.size() - 1);
            ObjectNode cols = out.putObject("columns");
            for (int c = 0; c < idx.length; c++) {
                ObjectNode s = cols.putObject(wanted.get(c));
                s.put("count", count[c]);
                s.put("missing", missing[c]);
                s.put("sum", sum[c]);
                if (count[c] > 0) {
                    s.put("mean", sum[c].divide(BigDecimal.valueOf(count[c]), 6, RoundingMode.HALF_UP));
                    s.put("min", min[c]);
                    s.put("max", max[c]);
                }
            }
            return out;
        }
    }

    /** JSON export of one project (and its recent jobs), stored in the job result and served by the download endpoint. */
    @Component
    public static class ProjectExport implements JobProcessor {
        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;

        public ProjectExport(JdbcTemplate jdbc, ObjectMapper mapper) {
            this.jdbc = jdbc;
            this.mapper = mapper;
        }

        @Override public String type() { return "PROJECT_EXPORT"; }

        @Override
        public JsonNode process(Job job, JobContext ctx) throws Exception {
            if (job.projectId() == null) {
                throw new PermanentJobException("projectId is required");
            }
            // Always scoped by the job's workspace: an export can never read another tenant's project.
            List<ObjectNode> found = jdbc.query("SELECT id, name, description, status, created_at, updated_at FROM projects WHERE id = ? AND workspace_id = ?",
                    (rs, i) -> {
                        ObjectNode p = mapper.createObjectNode();
                        p.put("id", rs.getObject("id", UUID.class).toString());
                        p.put("name", rs.getString("name"));
                        p.put("description", rs.getString("description"));
                        p.put("status", rs.getString("status"));
                        p.put("createdAt", rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant().toString());
                        p.put("updatedAt", rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant().toString());
                        return p;
                    }, job.projectId(), job.workspaceId());
            if (found.isEmpty()) {
                throw new PermanentJobException("project not found in this workspace");
            }
            ctx.checkpoint();
            ObjectNode out = mapper.createObjectNode();
            out.put("exportVersion", 1);
            out.put("exportedAt", Instant.now().toString());
            out.put("workspaceId", job.workspaceId().toString());
            out.set("project", found.get(0));
            var jobs = out.putArray("jobs");
            jdbc.query("SELECT id, type, status, created_at FROM processing_jobs WHERE workspace_id = ? AND project_id = ? ORDER BY created_at DESC LIMIT 100",
                    rs -> {
                        ObjectNode j = jobs.addObject();
                        j.put("id", rs.getObject("id", UUID.class).toString());
                        j.put("type", rs.getString("type"));
                        j.put("status", rs.getString("status"));
                        j.put("createdAt", rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant().toString());
                    }, job.workspaceId(), job.projectId());
            out.put("sizeBytes", mapper.writeValueAsBytes(out).length);
            return out;
        }
    }
}
