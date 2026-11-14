package com.climind.saas.pricing;

/**
 * Problema de una configuración, con ubicación para que el constructor lo señale.
 *
 * @param section general | questions | pricing | commercial
 * @param item    key de la pregunta o id de la regla (null si aplica a la sección)
 * @param message mensaje para el dueño del negocio
 */
public record ConfigIssue(String section, String item, String message) {

    public static ConfigIssue general(String message) {
        return new ConfigIssue("general", null, message);
    }

    public static ConfigIssue question(String key, String message) {
        return new ConfigIssue("questions", key, message);
    }

    public static ConfigIssue rule(String id, String message) {
        return new ConfigIssue("pricing", id, message);
    }

    public static ConfigIssue pricing(String message) {
        return new ConfigIssue("pricing", null, message);
    }

    public static ConfigIssue commercial(String message) {
        return new ConfigIssue("commercial", null, message);
    }
}
