package com.climind.saas.assistant;

import com.climind.saas.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Asistente de ayuda del panel (requiere sesión). */
@Tag(name = "Asistente")
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @Operation(summary = "Si el asistente responde con IA o solo con las preguntas frecuentes")
    @GetMapping("/status")
    public AssistantService.Status status() {
        return service.status();
    }

    @Operation(summary = "Preguntarle al asistente (la conversación la guarda el navegador)")
    @PostMapping("/chat")
    public AssistantService.ChatReply chat(@Valid @RequestBody AssistantService.ChatRequest request) {
        return service.chat(CurrentUser.require(), request);
    }
}
