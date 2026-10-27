package io.nettriage.service.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.nettriage.core.tools.ToolDefinition;
import io.nettriage.core.tools.TriageTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes the NetTriage tools over the Model Context Protocol (streamable HTTP at
 * {@code /mcp}), so any MCP client, such as Claude Code, Claude Desktop or the
 * built-in agent, can investigate incidents. Tool calls are logged with the client's
 * name; the one tool that is not read-only only files a request for a person.
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    private final JsonMapper json = JsonMapper.builder().build();

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(json))
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> reg =
                new ServletRegistrationBean<>(transport, "/mcp", "/mcp/*");
        reg.setAsyncSupported(true);
        reg.setName("mcp");
        return reg;
    }

    @Bean(destroyMethod = "close")
    McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, TriageTools tools) {
        var spec = McpServer.sync(transport)
                .serverInfo("nettriage", "0.1.0")
                .instructions(TriageTools.INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for (ToolDefinition def : TriageTools.definitions()) {
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(def.name())
                    .title(def.title())
                    .description(def.description())
                    .inputSchema(def.inputSchema())
                    .annotations(McpSchema.ToolAnnotations.builder()
                            .title(def.title())
                            .readOnlyHint(def.readOnly())
                            .destructiveHint(false)
                            .idempotentHint(def.readOnly())
                            .openWorldHint(false)
                            .build())
                    .build();
            spec.toolCall(tool, (exchange, req) -> call(tools, def, exchange, req));
        }
        return spec.build();
    }

    private McpSchema.CallToolResult call(TriageTools tools, ToolDefinition def, McpSyncServerExchange exchange,
                                          McpSchema.CallToolRequest req) {
        String client = exchange.getClientInfo() == null ? "unknown" : exchange.getClientInfo().name();
        try {
            Object result = tools.call(def.name(), req.arguments(), "mcp:" + client);
            log.info("mcp {} called {} {}", client, def.name(), req.arguments());
            return McpSchema.CallToolResult.builder().addTextContent(json.writeValueAsString(result)).build();
        } catch (IllegalArgumentException e) {
            return McpSchema.CallToolResult.builder().addTextContent(e.getMessage()).isError(true).build();
        }
    }
}
