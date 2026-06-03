package pt.properia.api.modules.search.application.dto;

public record PriceHistorySnapshotDto(
    String trend,        // "down" | "up" | "stable"
    Double deltaPct,     // magnitude positiva em %, null se stable
    Double deltaAmount,  // valor absoluto em €, null se stable
    String lastChangeAt, // ISO instant da última alteração, null se stable
    int changeCount
) {}
