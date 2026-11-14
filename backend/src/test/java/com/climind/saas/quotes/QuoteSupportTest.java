package com.climind.saas.quotes;

import com.climind.saas.pricing.PriceBreakdown.AnsweredQuestion;
import com.climind.saas.shared.util.ArMoney;
import com.climind.saas.shared.util.Phones;
import com.climind.saas.shared.util.QrCodes;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Piezas puras de la Fase 3: teléfonos, importes, WhatsApp, CSV y QR. */
class QuoteSupportTest {

    @Nested
    class TelefonosDeWhatsapp {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "11 2345-6789, 5491123456789",
                "011 15 2345-6789, 5491123456789",
                "(0351) 15 234-5678, 5493512345678",
                "+54 9 11 2345-6789, 5491123456789",
                "+54 11 2345-6789, 5491123456789",
                "5491123456789, 5491123456789",
                "0054 9 261 444 5566, 5492614445566",
                "+1 (415) 555-2671, 14155552671",
        })
        void normalizaAlFormatoDeWaMe(String raw, String expected) {
            assertThat(Phones.whatsapp(raw, "AR")).contains(expected);
        }

        @Test
        void formatoParaMostrar() {
            assertThat(Phones.display("5491123456789")).isEqualTo("+54 9 11 2345-6789");
            assertThat(Phones.display("5493512345678")).isEqualTo("+54 9 351 234-5678");
            assertThat(Phones.display("14155552671")).isEqualTo("+14155552671");
        }

        @Test
        void rechazaNumerosInvalidos() {
            assertThat(Phones.whatsapp("123", "AR")).isEmpty();
            assertThat(Phones.whatsapp("", "AR")).isEmpty();
            assertThat(Phones.whatsapp(null, "AR")).isEmpty();
            assertThat(Phones.whatsapp("1234567890123456", "AR")).isEmpty();
        }
    }

    @Test
    void importesEnFormatoArgentino() {
        assertThat(ArMoney.format(new BigDecimal("185500"), 0)).isEqualTo("$ 185.500");
        assertThat(ArMoney.format(new BigDecimal("185500.5"), 2)).isEqualTo("$ 185.500,50");
        assertThat(ArMoney.format(new BigDecimal("999.5"), 0)).isEqualTo("$ 1.000");
        assertThat(ArMoney.format(new BigDecimal("-1500"), 0)).isEqualTo("-$ 1.500");
        assertThat(ArMoney.plain(new BigDecimal("185500.5"))).isEqualTo("185500,50");
    }

    @Test
    void mensajeYEnlaceDeWhatsapp() {
        String message = QuoteTexts.customerMessage(
                "Hola, me interesa {servicio} por {total}. Código {codigo}.", "Limpieza & más",
                new BigDecimal("85000"), 0, "COT-ABC234");
        assertThat(message).isEqualTo("Hola, me interesa Limpieza & más por $ 85.000. Código COT-ABC234.");
        String url = QuoteTexts.waUrl("5491123456789", message);
        assertThat(url).startsWith("https://wa.me/5491123456789?text=").doesNotContain(" ", "+", "&más");
        assertThat(URLDecoder.decode(url.substring(url.indexOf('=') + 1), StandardCharsets.UTF_8)).isEqualTo(message);
        assertThat(QuoteTexts.businessMessage("Carla Gómez", "Limpiezas Ana", "COT-ABC234", "Hogar"))
                .isEqualTo("Hola Carla, te escribo de Limpiezas Ana por tu consulta COT-ABC234 (Hogar).");
    }

    @Test
    void resumenDeRespuestasAcotado() {
        assertThat(QuoteTexts.summary(List.of(new AnsweredQuestion("tipo", "Tipo", "Profunda"),
                new AnsweredQuestion("m2", "Superficie", "60 m²")))).isEqualTo("Tipo: Profunda · Superficie: 60 m²");
        String longText = QuoteTexts.summary(List.of(new AnsweredQuestion("d", "Detalle", "x".repeat(500))));
        assertThat(longText).hasSize(300).endsWith("…");
    }

    @Test
    void celdasCsvSeguras() {
        assertThat(LeadCsv.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(LeadCsv.cell("+54911")).isEqualTo("'+54911");
        assertThat(LeadCsv.cell("-2")).isEqualTo("'-2");
        assertThat(LeadCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(LeadCsv.cell("Ruiz; Diego")).isEqualTo("\"Ruiz; Diego\"");
        assertThat(LeadCsv.cell("dice \"hola\"")).isEqualTo("\"dice \"\"hola\"\"\"");
        assertThat(LeadCsv.cell("línea 1\nlínea 2")).isEqualTo("\"línea 1\nlínea 2\"");
        assertThat(LeadCsv.cell(null)).isEmpty();
        assertThat(LeadCsv.cell("Normal")).isEqualTo("Normal");
    }

    @Test
    void elQrSeLeeYApuntaAlEnlace() throws Exception {
        String url = "https://cotiza.climind-ia.com/c/limpiezas-ana-7k2pq/limpieza-de-hogares";
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(QrCodes.png(url, 8)));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)))).getText();
        assertThat(decoded).isEqualTo(url);
        assertThat(QrCodes.svg(url)).startsWith("<svg").contains("viewBox=\"0 0 ").endsWith("</svg>");
    }

}
