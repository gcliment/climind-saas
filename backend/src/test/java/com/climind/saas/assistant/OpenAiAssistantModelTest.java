package com.climind.saas.assistant;

import com.climind.saas.shared.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Pedido y respuesta de OpenAI (Chat Completions), sin red. */
class OpenAiAssistantModelTest {

    private static AppProperties.Assistant config() {
        AppProperties.Assistant config = new AppProperties.Assistant();
        config.setApiKey("sk-test");
        return config;
    }

    @Test
    void mandaLaDocumentacionPrimeroElContextoDespuesYLaConversacion() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiAssistantModel model = new OpenAiAssistantModel(builder, config());

        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("DOCUMENTACIÓN"))
                .andExpect(jsonPath("$.messages[1].content").value("CONTEXTO"))
                .andExpect(jsonPath("$.messages[2].role").value("user"))
                .andExpect(jsonPath("$.messages[3].role").value("assistant"))
                .andExpect(jsonPath("$.messages[4].content").value("¿Y en el celular?"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"  Tocá **Menú**.  ","refusal":null}}],
                         "usage":{"prompt_tokens":5000,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":4096}}}
                        """, MediaType.APPLICATION_JSON));

        var reply = model.reply("DOCUMENTACIÓN", "CONTEXTO", List.of(
                new AssistantModel.Turn(true, "¿Dónde está el modo oscuro?"),
                new AssistantModel.Turn(false, "En el menú."),
                new AssistantModel.Turn(true, "¿Y en el celular?")));

        assertThat(reply).contains("Tocá **Menú**.");
        server.verify();
    }

    @Test
    void siDeclinaResponderNoDevuelveNada() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiAssistantModel model = new OpenAiAssistantModel(builder, config());
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":null,"refusal":"No puedo ayudar con eso."}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(model.reply("DOC", "CTX", List.of(new AssistantModel.Turn(true, "x")))).isEmpty();
    }
}
