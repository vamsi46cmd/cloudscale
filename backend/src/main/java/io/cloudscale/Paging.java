package io.cloudscale;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Paging {
    private Paging() {}

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        public static <E, T> PageResponse<T> of(Page<E> p, Function<E, T> mapper) {
            return new PageResponse<>(p.getContent().stream().map(mapper).toList(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
    }

    /** Size is capped at 100 and the sort field must be whitelisted (no arbitrary column names reach the query). */
    public static Pageable of(int page, int size, String sort, Set<String> allowed, String defaultField) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("page must be >= 0 and size between 1 and 100");
        }
        String field = defaultField;
        Sort.Direction dir = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            field = parts[0];
            if (parts.length > 1) {
                dir = "asc".equalsIgnoreCase(parts[1]) ? Sort.Direction.ASC : "desc".equalsIgnoreCase(parts[1]) ? Sort.Direction.DESC : null;
                if (dir == null) {
                    throw ApiException.badRequest("sort direction must be asc or desc");
                }
            }
        }
        if (!allowed.contains(field)) {
            throw ApiException.badRequest("sort must be one of " + allowed);
        }
        return PageRequest.of(page, size, Sort.by(dir, field).and(Sort.by(Sort.Direction.ASC, "id")));
    }
}
