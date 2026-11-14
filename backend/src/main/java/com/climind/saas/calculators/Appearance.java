package com.climind.saas.calculators;

import com.climind.saas.shared.web.ApiException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Personalización visual del cotizador público.
 *
 * @param accentColor color de botones y destacados (#RRGGBB). Debe tener contraste suficiente con texto blanco.
 * @param showLogo    mostrar el logo del negocio
 */
public record Appearance(String accentColor, Boolean showLogo) {

    public static final String DEFAULT_ACCENT = "#0071DB";
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    /** WCAG AA para texto normal: el texto de los botones es blanco sobre el color elegido. */
    private static final double MIN_CONTRAST = 4.5;

    public Appearance {
        accentColor = accentColor == null || accentColor.isBlank() ? DEFAULT_ACCENT : accentColor.toUpperCase(Locale.ROOT);
        showLogo = showLogo == null || showLogo;
    }

    public static Appearance defaults() {
        return new Appearance(DEFAULT_ACCENT, true);
    }

    public Appearance validated() {
        if (!HEX.matcher(accentColor).matches()) {
            throw ApiException.field("appearance.accentColor", "Elegí un color válido.");
        }
        if (contrastWithWhite(accentColor) < MIN_CONTRAST) {
            throw ApiException.field("appearance.accentColor",
                    "Ese color es muy claro: el texto de los botones no se leería. Elegí uno más oscuro.");
        }
        return this;
    }

    static double contrastWithWhite(String hex) {
        double luminance = 0.2126 * channel(hex, 1) + 0.7152 * channel(hex, 3) + 0.0722 * channel(hex, 5);
        return 1.05 / (luminance + 0.05);
    }

    private static double channel(String hex, int start) {
        double c = Integer.parseInt(hex.substring(start, start + 2), 16) / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
