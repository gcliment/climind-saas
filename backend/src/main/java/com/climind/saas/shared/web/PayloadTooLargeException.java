package com.climind.saas.shared.web;

import java.io.IOException;

/** El cuerpo de la solicitud supera el máximo permitido (ver {@link RequestSizeLimitFilter}). */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException() {
        super("Request body too large");
    }
}
