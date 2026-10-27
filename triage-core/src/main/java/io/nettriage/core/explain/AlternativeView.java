package io.nettriage.core.explain;

/**
 * A competing explanation and why it lost.
 *
 * @param margin log-likelihood by which it loses; under about 4 the two are hard to tell apart
 */
public record AlternativeView(String element, String description, int covered, int degraded, int overlap,
                              double margin, String reason) {
}
