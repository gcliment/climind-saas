package com.climind.saas.storage;

import java.util.Optional;

/**
 * Formatos de imagen aceptados, detectados por su firma binaria (magic bytes), nunca por la extensión ni
 * por el Content-Type que manda el navegador. SVG queda excluido a propósito: puede contener scripts.
 */
public enum ImageType {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp");

    private final String contentType;
    private final String extension;

    ImageType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<ImageType> detect(byte[] b) {
        if (b == null || b.length < 12) {
            return Optional.empty();
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return Optional.of(PNG);
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    public static Optional<ImageType> fromContentType(String contentType) {
        for (ImageType type : values()) {
            if (type.contentType.equals(contentType)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
