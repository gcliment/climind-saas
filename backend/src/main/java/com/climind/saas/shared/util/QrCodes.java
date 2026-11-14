package com.climind.saas.shared.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Códigos QR generados en el servidor (sin librerías en el navegador). */
public final class QrCodes {

    private static final int QUIET_ZONE = 4;

    private QrCodes() {
    }

    private static BitMatrix matrix(String text) {
        try {
            // Tamaño 0: ZXing devuelve la matriz mínima (1 módulo = 1 unidad); cada formato aplica su escala.
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, QUIET_ZONE,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalArgumentException("No se pudo generar el código QR", e);
        }
    }

    /** SVG escalable: un único path con los módulos oscuros sobre fondo blanco. */
    public static String svg(String text) {
        BitMatrix m = matrix(text);
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight()
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"
                + "<path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }

    /** PNG para imprimir (cada módulo ocupa {@code scale} píxeles). */
    public static byte[] png(String text, int scale) {
        BitMatrix m = matrix(text);
        BufferedImage image = new BufferedImage(m.getWidth() * scale, m.getHeight() * scale,
                BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, m.get(x / scale, y / scale) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
