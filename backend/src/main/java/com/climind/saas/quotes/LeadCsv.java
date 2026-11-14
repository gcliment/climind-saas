package com.climind.saas.quotes;

import com.climind.saas.shared.util.ArMoney;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Exportación de consultas a CSV para Excel/Google Sheets en español: separador ";", BOM UTF-8 y coma
 * decimal. Neutraliza fórmulas (inyección CSV): una celda que empieza con = + - @ se antepone con '.
 */
final class LeadCsv {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Pattern FORMULA_START = Pattern.compile("^[=+\\-@\\t\\r].*", Pattern.DOTALL);
    private static final String HEADER = String.join(";", "Código", "Fecha", "Estado", "Nombre", "WhatsApp", "Email",
            "Cotizador", "Importe estimado", "Moneda", "Resumen", "Comentarios");

    private LeadCsv() {
    }

    static String write(List<QuoteRequest> leads, ZoneId zone) {
        StringBuilder out = new StringBuilder("\uFEFF").append(HEADER).append("\r\n");
        for (QuoteRequest lead : leads) {
            out.append(String.join(";",
                    cell(lead.getCode()),
                    cell(DATE_TIME.format(lead.getCreatedAt().atZone(zone))),
                    cell(lead.getStatus().label()),
                    cell(lead.isAnonymized() ? "(datos eliminados)" : lead.getCustomerName()),
                    // "wa.me/549..." queda como texto (Excel no lo convierte a número) y sirve para abrir el chat.
                    cell(lead.getCustomerWhatsapp() == null ? null : "wa.me/" + lead.getCustomerWhatsapp()),
                    cell(lead.getCustomerEmail()),
                    cell(lead.getCalculatorName()),
                    cell(ArMoney.plain(lead.getTotal())),
                    cell(lead.getCurrency()),
                    cell(lead.getSummary()),
                    cell(lead.getComments()))).append("\r\n");
        }
        return out.toString();
    }

    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = FORMULA_START.matcher(value).matches() ? "'" + value : value;
        if (text.contains(";") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
