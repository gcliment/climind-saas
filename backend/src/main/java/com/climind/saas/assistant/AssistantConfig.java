package com.climind.saas.assistant;

import com.climind.saas.shared.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** El modelo de IA existe solo con OPENAI_API_KEY configurada; sin ella el asistente usa las preguntas frecuentes. */
@Configuration
public class AssistantConfig {

    @Bean
    @ConditionalOnExpression("'${app.assistant.api-key:}'.trim().length() > 0")
    public AssistantModel openAiAssistantModel(AppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(properties.getAssistant().getTimeout());
        return new OpenAiAssistantModel(RestClient.builder().requestFactory(factory), properties.getAssistant());
    }
}
