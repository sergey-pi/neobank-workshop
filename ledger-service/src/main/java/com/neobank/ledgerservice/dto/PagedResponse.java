package com.neobank.ledgerservice.dto;

import java.util.List;

/** Generic paginated response envelope for list endpoints. */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        int totalPages,
        long totalItems,
        boolean hasMore) {

    public static <T> PagedResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = (int) Math.ceil((double) totalItems / size);
        return new PagedResponse<>(items, page, size, totalPages, totalItems, page < totalPages - 1);
    }
}
