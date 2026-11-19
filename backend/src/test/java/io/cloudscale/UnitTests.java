package io.cloudscale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudscale.Entities.Role;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** Unit tests that need no database or Redis. */
class UnitTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- roles ----
    @Test
    void roleOrderingIsOwnerAdminMember() {
        assertTrue(Role.OWNER.atLeast(Role.ADMIN));
        assertTrue(Role.ADMIN.atLeast(Role.MEMBER));
        assertFalse(Role.MEMBER.atLeast(Role.ADMIN));
        assertFalse(Role.ADMIN.atLeast(Role.OWNER));
    }

    // ---- cache keys: always tenant-scoped ----
    @Test
    void cacheKeysAreNamespacedAndTenantScoped() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        assertTrue(CacheService.workspaceKey(a).startsWith("cs:v1:ws:" + a + ":"));
        assertTrue(CacheService.projectKey(a, p).startsWith("cs:v1:ws:" + a + ":"));
        assertFalse(CacheService.projectKey(a, p).equals(CacheService.projectKey(b, p)), "same project id under another tenant must not collide");
    }

    // ---- retry backoff ----
    @Test
    void backoffDoublesAndIsCapped() {
        Duration base = Duration.ofSeconds(2);
        assertEquals(Duration.ofSeconds(2), JobWorker.backoff(1, base));
        assertEquals(Duration.ofSeconds(4), JobWorker.backoff(2, base));
        assertEquals(Duration.ofSeconds(8), JobWorker.backoff(3, base));
        assertEquals(Duration.ofSeconds(300), JobWorker.backoff(30, base));
    }

    // ---- paging ----
    @Test
    void pagingRejectsBadSizeAndUnknownSortField() {
        Set<String> allowed = Set.of("createdAt", "name");
        Pageable ok = Paging.of(0, 20, "name,asc", allowed, "createdAt");
        assertEquals("name", ok.getSort().iterator().next().getProperty());
        assertThrows(ApiException.class, () -> Paging.of(0, 101, null, allowed, "createdAt"));
        assertThrows(ApiException.class, () -> Paging.of(-1, 10, null, allowed, "createdAt"));
        assertThrows(ApiException.class, () -> Paging.of(0, 10, "password_hash,asc", allowed, "createdAt"));
        assertThrows(ApiException.class, () -> Paging.of(0, 10, "name,sideways", allowed, "createdAt"));
    }

    // ---- CSV parsing ----
    @Test
    void csvParsesQuotesEscapesAndCrlf() {
        List<String[]> rows = Csv.parse("a,b\r\n\"x,1\",\"he said \"\"hi\"\"\"\r\n\r\n3,4\n", 100);
        assertEquals(3, rows.size());
        assertEquals("x,1", rows.get(1)[0]);
        assertEquals("he said \"hi\"", rows.get(1)[1]);
        assertEquals("4", rows.get(2)[1]);
    }

    @Test
    void csvRejectsUnterminatedQuoteAndTooManyRows() {
        assertThrows(PermanentJobException.class, () -> Csv.parse("a\n\"oops", 10));
        assertThrows(PermanentJobException.class, () -> Csv.parse("a\n1\n2\n3\n4\n", 3));
    }

    // ---- CSV analytics ----
    private JsonNode analytics(String csv, String... columns) throws Exception {
        var payload = MAPPER.createObjectNode().put("csv", csv);
        var cols = payload.putArray("columns");
        for (String c : columns) {
            cols.add(c);
        }
        Job job = new Job(UUID.randomUUID(), UUID.randomUUID(), null, "CSV_ANALYTICS", "RUNNING", payload, null, null, 1, 3, false,
                null, null, null, null, null, null);
        return new JobProcessors.CsvAnalytics(MAPPER).process(job, null);
    }

    @Test
    void csvAnalyticsComputesExactCountSumMeanMinMax() throws Exception {
        JsonNode r = analytics("name,price,qty\na,0.10,1\nb,0.20,\nc,10.50,3\n", "price", "qty");
        assertEquals(3, r.get("rows").asInt());
        JsonNode price = r.get("columns").get("price");
        assertEquals(3, price.get("count").asInt());
        assertEquals(new BigDecimal("10.80"), price.get("sum").decimalValue());   // exact: 0.10 + 0.20 + 10.50
        assertEquals(new BigDecimal("3.600000"), price.get("mean").decimalValue());
        assertEquals(new BigDecimal("0.10"), price.get("min").decimalValue());
        assertEquals(new BigDecimal("10.50"), price.get("max").decimalValue());
        JsonNode qty = r.get("columns").get("qty");
        assertEquals(2, qty.get("count").asInt());
        assertEquals(1, qty.get("missing").asInt());
    }

    @Test
    void csvAnalyticsFailsPermanentlyOnBadInput() {
        assertThrows(PermanentJobException.class, () -> analytics("a,b\n1,2\n", "missing_column"));
        assertThrows(PermanentJobException.class, () -> analytics("a\nnot-a-number\n", "a"));
        assertThrows(PermanentJobException.class, () -> analytics("   ", "a"));
    }
}
