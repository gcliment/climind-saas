package com.climind.saas.shared.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Importes en formato argentino: {@code $ 185.500} o {@code $ 185.500,50}. Igual que format.js del frontend. */
public final class ArMoney {

    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR"));

    private ArMoney() {
    }

    /**
     * @param scale 0 = pesos enteros, 2 = con centavos
     */
    public static String format(BigDecimal amount, int scale) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount).setScale(scale, RoundingMode.HALF_UP);
        DecimalFormat format = new DecimalFormat(scale == 0 ? "#,##0" : "#,##0.00", SYMBOLS);
        String text = format.format(value.abs());
        return (value.signum() < 0 ? "-$ " : "$ ") + text;
    }

    /** Número para planillas en español: sin separador de miles y con coma decimal ({@code 185500,50}). */
    public static String plain(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP).toPlainString()
                .replace('.', ',');
    }
}
