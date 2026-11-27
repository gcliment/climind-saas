package com.climind.saas.assistant;

import java.util.List;
import java.util.Optional;

/** Modelo de lenguaje que redacta las respuestas del asistente. */
public interface AssistantModel {

    /** Un turno de la conversación. {@code fromUser} = lo escribió la persona; si no, el asistente. */
    record Turn(boolean fromUser, String text) {
    }

    /**
     * @param stableSystem instrucciones y documentación: iguales en todos los pedidos (se cachean)
     * @param userContext  datos de este usuario y de los planes vigentes (cambian por pedido)
     * @param turns        conversación, empezando y terminando con un turno del usuario
     * @return la respuesta, o vacío si el modelo la declinó o no la pudo dar
     */
    Optional<String> reply(String stableSystem, String userContext, List<Turn> turns);
}
