package com.climind.saas.businesses;

/** Rubros de actividad. El código se persiste; la etiqueta se muestra en la interfaz. */
public enum Industry {
    LIMPIEZA("Limpieza"),
    CATERING_EVENTOS("Catering y eventos"),
    INSTALACIONES("Instalaciones y servicios técnicos"),
    CONSTRUCCION("Construcción y reformas"),
    MANTENIMIENTO("Mantenimiento y reparaciones"),
    JARDINERIA("Jardinería y paisajismo"),
    MUDANZAS_FLETES("Mudanzas y fletes"),
    BELLEZA_ESTETICA("Belleza y estética"),
    FOTOGRAFIA_VIDEO("Fotografía y video"),
    IMPRENTA_DISENO("Imprenta, diseño y personalizados"),
    CARPINTERIA_HERRERIA("Carpintería y herrería"),
    TECNOLOGIA("Tecnología e informática"),
    EDUCACION("Clases y capacitación"),
    SALUD_BIENESTAR("Salud y bienestar"),
    TURISMO("Turismo y alojamiento"),
    OTRO("Otro rubro");

    private final String label;

    Industry(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
