package io.nettriage.core.explain;

import java.util.Map;

/**
 * One checkable fact behind a diagnosis. The id ({@code E1}, {@code E2}, ...) is what an
 * AI agent must cite when it makes a claim, so every sentence in a write-up can be
 * traced to numbers the engine actually measured.
 *
 * @param data the numbers in the sentence, machine-readable
 */
public record Evidence(String id, String text, Map<String, Object> data) {
}
