package io.nettriage.service;

import io.nettriage.core.TriageEngine;
import io.nettriage.core.tools.TriageTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {

    @Bean
    TriageEngine triageEngine() {
        return new TriageEngine();
    }

    @Bean
    TriageTools triageTools(TriageEngine engine, ActionService actions) {
        return new TriageTools(engine, actions);
    }
}
