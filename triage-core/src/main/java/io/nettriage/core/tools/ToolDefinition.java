package io.nettriage.core.tools;

import java.util.Map;

/**
 * A tool an AI agent can call: what MCP exposes and what the built-in agent sends to
 * the model. {@code inputSchema} is JSON Schema as nested maps.
 *
 * @param readOnly true when calling it cannot change anything
 */
public record ToolDefinition(String name, String title, String description, Map<String, Object> inputSchema,
                             boolean readOnly) {
}
