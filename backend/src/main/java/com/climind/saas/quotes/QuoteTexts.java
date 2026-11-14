package com.climind.saas.quotes;

import com.climind.saas.pricing.PriceBreakdown;
import com.climind.saas.shared.util.ArMoney;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/** Textos derivados de una cotización: mensajes de WhatsApp (enlaces oficiales wa.me) y resúmenes. */
final class QuoteTexts {

    private static final int SUMMARY_MAX = 300;

    private QuoteTexts() {
    }

    /** Enlace oficial de WhatsApp (click to chat). Sin número abre el selector de contactos. */
    static String waUrl(String digits, String text) {
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://wa.me/" + (digits == null ? "" : digits) + "?text=" + encoded;
    }

    /** Mensaje del cliente al negocio, con la plantilla que configuró el negocio en el cotizador. */
    static String customerMessage(String template, String calculatorName, BigDecimal total, int scale, String code) {
        return template
                .replace("{servicio}", calculatorName)
                .replace("{total}", ArMoney.format(total, scale))
                .replace("{codigo}", code);
    }

    /** Mensaje sugerido del negocio al cliente, para iniciar la conversación desde el panel. */
    static String businessMessage(String customerName, String businessName, String code, String calculatorName) {
        String firstName = customerName == null ? "" : " " + customerName.strip().split("\\s+")[0];
        return "Hola" + firstName + ", te escribo de " + businessName + " por tu consulta " + code
                + " (" + calculatorName + ").";
    }

    /** "Tipo de limpieza: Profunda · Superficie: 60 m²" para listados y exportaciones. */
    static String summary(List<PriceBreakdown.AnsweredQuestion> answered) {
        String text = answered.stream()
                .map(a -> a.label() + ": " + a.value())
                .collect(Collectors.joining(" · "));
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX - 1).strip() + "…";
    }
}
