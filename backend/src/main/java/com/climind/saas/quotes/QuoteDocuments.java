package com.climind.saas.quotes;

import com.climind.saas.businesses.BusinessLogoService;
import com.climind.saas.businesses.PublicBusinesses;
import com.climind.saas.calculators.ConfigJson;
import com.climind.saas.pricing.CalculatorConfig.TaxMode;
import com.climind.saas.pricing.PriceBreakdown;
import com.climind.saas.pricing.PriceBreakdown.Line;
import com.climind.saas.pricing.PriceBreakdown.LineKind;
import com.climind.saas.shared.util.ArMoney;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * PDF de una cotización: documento sobrio para imprimir o reenviar, armado con el snapshot guardado
 * (nunca se recalcula). Aclara que es una estimación y que no es una factura ni un comprobante fiscal.
 */
@Component
public class QuoteDocuments {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", ES_AR);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final String FONT = "Noto Sans";

    private final PublicBusinesses businesses;
    private final ConfigJson json;
    private final com.climind.saas.subscriptions.EntitlementService entitlements;
    /** Motor propio en modo XML (el renderizador de PDF necesita XHTML bien formado), con expresiones SpEL. */
    private final SpringTemplateEngine engine = new SpringTemplateEngine();

    public QuoteDocuments(PublicBusinesses businesses, ConfigJson json,
                          com.climind.saas.subscriptions.EntitlementService entitlements) {
        this.businesses = businesses;
        this.json = json;
        this.entitlements = entitlements;
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/pdf/");
        resolver.setSuffix(".xhtml");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        engine.setTemplateResolver(resolver);
    }

    public record Pdf(String filename, byte[] content) {
    }

    public record Row(String label, String value) {
    }

    public record DocLine(String label, String detail, String amount, boolean discount) {
    }

    public Pdf pdf(QuoteSimulation simulation, QuoteRequest lead) {
        PriceBreakdown breakdown = json.read(simulation.getResult(), PriceBreakdown.class);
        int scale = simulation.getPriceScale();
        Optional<PublicBusinesses.PublicBusiness> business = businesses.find(simulation.getTenantId());

        Context ctx = new Context(ES_AR);
        ctx.setVariable("businessName", business.map(PublicBusinesses.PublicBusiness::name).orElse(""));
        ctx.setVariable("businessContact", business.map(QuoteDocuments::contactLine).orElse(""));
        ctx.setVariable("logo", business.flatMap(b -> businesses.logo(b.logoAssetId())).flatMap(QuoteDocuments::dataUri)
                .orElse(null));
        ctx.setVariable("code", simulation.getCode());
        ctx.setVariable("date", DATE.format(simulation.getCreatedAt().atZone(DEFAULT_ZONE)));
        ctx.setVariable("customerName", lead == null || lead.isAnonymized() ? null : lead.getCustomerName());
        ctx.setVariable("calculatorName", simulation.getCalculatorName());
        ctx.setVariable("answers", breakdown.answered().stream().map(a -> new Row(a.label(), a.value())).toList());
        ctx.setVariable("lines", breakdown.lines().stream().map(l -> docLine(l, scale)).toList());
        ctx.setVariable("totals", totals(breakdown, scale));
        ctx.setVariable("total", ArMoney.format(breakdown.total(), scale));
        ctx.setVariable("taxNote", breakdown.taxMode() == TaxMode.EXCLUDED
                ? Optional.ofNullable(breakdown.taxLabel()).orElse("Los precios no incluyen impuestos.") : null);
        ctx.setVariable("validity", simulation.getValidUntil() == null ? null
                : "Precio válido hasta el " + DATE.format(simulation.getValidUntil()) + ".");
        ctx.setVariable("disclaimer", simulation.getDisclaimer());
        ctx.setVariable("branding", !entitlements.forTenant(simulation.getTenantId()).customBranding());
        String xhtml = engine.process("quote", ctx);
        return new Pdf("cotizacion-" + simulation.getCode() + ".pdf", render(xhtml));
    }

    private static List<Row> totals(PriceBreakdown b, int scale) {
        List<Row> rows = new ArrayList<>();
        boolean adjusted = b.surchargesTotal().signum() != 0 || b.discountsTotal().signum() != 0
                || b.minimumAdjustment().signum() != 0 || (b.taxAmount() != null && b.taxAmount().signum() != 0);
        if (adjusted) {
            rows.add(new Row("Subtotal", ArMoney.format(b.subtotal(), amountScale(b.subtotal(), scale))));
            if (b.surchargesTotal().signum() != 0) {
                rows.add(new Row("Adicionales", ArMoney.format(b.surchargesTotal(), amountScale(b.surchargesTotal(), scale))));
            }
            if (b.discountsTotal().signum() != 0) {
                rows.add(new Row("Descuentos", "-" + ArMoney.format(b.discountsTotal(), amountScale(b.discountsTotal(), scale))));
            }
        }
        if (b.roundingAdjustment() != null && b.roundingAdjustment().signum() != 0 && scale == 0) {
            rows.add(new Row("Redondeo", ArMoney.format(b.roundingAdjustment(), 2)));
        }
        return rows;
    }

    private static DocLine docLine(Line line, int scale) {
        String detail = null;
        if (line.quantity() != null && line.unitPrice() != null) {
            detail = plain(line.quantity()) + (line.unit() == null || line.unit().isBlank() ? "" : " " + line.unit())
                    + " × " + ArMoney.format(line.unitPrice(), line.unitPrice().stripTrailingZeros().scale() > 0 ? 2 : 0);
        } else if (line.percent() != null) {
            detail = (line.kind() == LineKind.DISCOUNT ? "-" : "+") + plain(line.percent()) + " %";
        }
        BigDecimal amount = line.amount();
        int decimals = amountScale(amount, scale);
        String text = amount.signum() < 0 ? "-" + ArMoney.format(amount.negate(), decimals) : ArMoney.format(amount, decimals);
        return new DocLine(line.label() == null || line.label().isBlank() ? "Concepto" : line.label(), detail, text,
                line.kind() == LineKind.DISCOUNT);
    }

    /** En cotizadores de pesos enteros, los importes redondos se muestran sin ",00". */
    private static int amountScale(BigDecimal amount, int priceScale) {
        return priceScale == 0 && amount.stripTrailingZeros().scale() <= 0 ? 0 : 2;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String contactLine(PublicBusinesses.PublicBusiness b) {
        List<String> parts = new ArrayList<>();
        if (b.whatsapp() != null) {
            parts.add("WhatsApp " + com.climind.saas.shared.util.Phones.display(b.whatsapp()));
        }
        if (b.phone() != null) {
            parts.add("Tel. " + b.phone());
        }
        if (b.contactEmail() != null) {
            parts.add(b.contactEmail());
        }
        if (b.website() != null) {
            parts.add(b.website().replaceFirst("^https?://", ""));
        }
        return String.join("  ·  ", parts);
    }

    /** PNG y JPG se incrustan; WebP no lo soporta el renderizador de PDF (el documento sale sin logo). */
    private static Optional<String> dataUri(BusinessLogoService.StoredFile file) {
        if (!Map.of("image/png", true, "image/jpeg", true).containsKey(file.contentType())) {
            return Optional.empty();
        }
        try (InputStream in = file.resource().getInputStream()) {
            return Optional.of("data:" + file.contentType() + ";base64,"
                    + Base64.getEncoder().encodeToString(in.readAllBytes()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static byte[] render(String xhtml) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFont(() -> font("NotoSans-Regular.ttf"), FONT, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> font("NotoSans-Bold.ttf"), FONT, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            // Solo recursos incrustados (data:): el documento nunca descarga nada de la red ni del disco.
            builder.useUriResolver((base, uri) -> uri != null && uri.startsWith("data:") ? uri : null);
            builder.withHtmlContent(xhtml, "about:blank");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream font(String file) {
        InputStream in = QuoteDocuments.class.getResourceAsStream("/pdf/fonts/" + file);
        if (in == null) {
            throw new IllegalStateException("Falta la fuente " + file);
        }
        return in;
    }
}
