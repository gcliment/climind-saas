package com.climind.saas.quotes;

import com.climind.saas.businesses.PublicBusinesses;
import com.climind.saas.calculators.Appearance;
import com.climind.saas.calculators.ConfigJson;
import com.climind.saas.calculators.PublicCalculators;
import com.climind.saas.calculators.PublicCalculators.PublishedCalculator;
import com.climind.saas.calculators.PublicCalculators.QuoteContext;
import com.climind.saas.pricing.CalculatorConfig;
import com.climind.saas.pricing.InvalidAnswersException;
import com.climind.saas.pricing.PriceBreakdown;
import com.climind.saas.pricing.PricingEngine;
import com.climind.saas.quotes.PublicQuoteDtos.BusinessCard;
import com.climind.saas.quotes.PublicQuoteDtos.ContactRequest;
import com.climind.saas.quotes.PublicQuoteDtos.CreateQuoteRequest;
import com.climind.saas.quotes.PublicQuoteDtos.PublicCalculator;
import com.climind.saas.quotes.PublicQuoteDtos.QuoteView;
import com.climind.saas.shared.ratelimit.RateLimiter;
import com.climind.saas.shared.util.Phones;
import com.climind.saas.shared.util.Tokens;
import com.climind.saas.shared.web.ApiError;
import com.climind.saas.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cotizador público: formulario, cálculo en el servidor, cotizaciones guardadas y consultas de clientes.
 * <ul>
 *   <li>El importe lo calcula SIEMPRE el servidor con la versión publicada (el navegador solo muestra).</li>
 *   <li>Solo se guarda una cotización cuando las respuestas son válidas: los intentos fallidos no cuentan.</li>
 *   <li>Mismo visitante + misma versión + mismas respuestas dentro de 30 minutos = la misma cotización
 *       (recalcular o recargar la página no infla las estadísticas ni, en la Fase 4, el cupo del plan).</li>
 *   <li>La cotización se consulta con código + token; el código solo (que viaja por WhatsApp) no da acceso.</li>
 * </ul>
 */
@Service
public class PublicQuoteService {

    static final Duration DEDUP_WINDOW = Duration.ofMinutes(30);
    /** Sin 0/O ni 1/I para que el código se pueda dictar por teléfono. */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final Pattern CODE = Pattern.compile("^COT-[A-Z0-9]{6}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PublicCalculators calculators;
    private final PublicBusinesses businesses;
    private final QuoteSimulationRepository simulations;
    private final QuoteRequestRepository requests;
    private final QuoteHistoryRepository history;
    private final QuoteDocuments documents;
    private final LeadNotifier notifier;
    private final ConfigJson json;
    private final RateLimiter rateLimiter;
    private final com.climind.saas.subscriptions.UsageService usage;
    private final Clock clock;

    public PublicQuoteService(PublicCalculators calculators, PublicBusinesses businesses,
                              QuoteSimulationRepository simulations, QuoteRequestRepository requests,
                              QuoteHistoryRepository history, QuoteDocuments documents, LeadNotifier notifier,
                              ConfigJson json, RateLimiter rateLimiter,
                              com.climind.saas.subscriptions.UsageService usage, Clock clock) {
        this.calculators = calculators;
        this.businesses = businesses;
        this.simulations = simulations;
        this.requests = requests;
        this.history = history;
        this.documents = documents;
        this.notifier = notifier;
        this.json = json;
        this.rateLimiter = rateLimiter;
        this.usage = usage;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ formulario

    @Transactional(readOnly = true)
    public PublicCalculator calculator(String tenantSlug, String calculatorSlug) {
        PublishedCalculator pc = published(tenantSlug, calculatorSlug);
        BusinessCard card = card(pc.tenantId(), pc.appearance());
        // Pausado, fuera del cupo de cotizadores del plan o sin cotizaciones disponibles en el período: se
        // muestran los datos de contacto del negocio en lugar del formulario (al visitante no le importa el motivo).
        if (!pc.available() || usage.quoteUsage(pc.tenantId()).exhausted()) {
            return new PublicCalculator(false, pc.name(), null, pc.appearance().accentColor(), card, null, null, null,
                    null, null, null, pc.showBranding());
        }
        CalculatorConfig config = pc.config();
        return new PublicCalculator(true, pc.name(), pc.description(), pc.appearance().accentColor(), card,
                json.tree(json.write(config.questions())), config.currency(), config.pricing().rounding().scale(),
                config.commercial().disclaimer(), config.commercial().validityDays(),
                config.commercial().leadCapture().name(), pc.showBranding());
    }

    // ------------------------------------------------------------------ cotizar

    @Transactional
    public QuoteView create(String tenantSlug, String calculatorSlug, CreateQuoteRequest request, String visitorKey,
                            String ip) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.PUBLIC_QUOTE_IP, ip)) {
            throw ApiException.tooManyRequests();
        }
        PublishedCalculator pc = published(tenantSlug, calculatorSlug);
        if (!pc.available()) {
            throw ApiException.conflict("CALCULATOR_UNAVAILABLE", "Este cotizador no está disponible por el momento.");
        }
        CalculatorConfig config = pc.config();
        boolean contactFirst = config.commercial().leadCapture() == CalculatorConfig.LeadCapture.BEFORE_ESTIMATE;
        if (contactFirst && request.contact() == null) {
            throw ApiException.badRequest("CONTACT_REQUIRED", "Completá tus datos para ver el precio estimado.");
        }
        Contact contact = request.contact() == null ? null : validate(request.contact(), pc.country(), "contact.");

        LocalDate today = LocalDate.now(clock.withZone(pc.zone()));
        PriceBreakdown breakdown;
        try {
            breakdown = PricingEngine.calculate(config, request.answers(), PricingEngine.Mode.STRICT, today);
        } catch (InvalidAnswersException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANSWERS", e.issues().getFirst().message(),
                    e.issues().stream().map(i -> new ApiError.FieldError(i.question(), i.message())).toList());
        }
        if (contact != null && !rateLimiter.tryConsume(RateLimiter.Policy.PUBLIC_LEAD_IP, ip)) {
            throw ApiException.tooManyRequests();
        }

        String answersJson = json.write(breakdown.answers());
        String answersHash = Tokens.sha256Hex(answersJson);
        String visitorHash = visitorKey == null ? null : Tokens.sha256Hex("visitor:" + visitorKey);
        Instant now = clock.instant();

        QuoteSimulation simulation = visitorHash == null ? null : simulations
                .findFirstByCalculatorVersionIdAndVisitorHashAndAnswersHashAndCreatedAtAfterOrderByCreatedAtDesc(
                        pc.versionId(), visitorHash, answersHash, now.minus(DEDUP_WINDOW))
                .orElse(null);
        if (simulation == null) {
            // Solo una cotización NUEVA y válida consume cupo (los recálculos deduplicados no).
            if (!usage.tryConsumeQuote(pc.tenantId())) {
                throw ApiException.conflict("QUOTA_EXCEEDED",
                        "Este cotizador no está disponible por el momento. Podés consultar directamente al negocio.");
            }
            Integer validity = config.commercial().validityDays();
            simulation = simulations.save(new QuoteSimulation(pc.tenantId(), pc.calculatorId(), pc.versionId(),
                    newCode(), Tokens.newToken(), pc.name(), answersJson, json.write(breakdown), breakdown.total(),
                    breakdown.currency(), config.pricing().rounding().scale(), config.commercial().disclaimer(),
                    validity == null ? null : today.plusDays(validity), answersHash, visitorHash, now));
        }
        QuoteRequest lead = requests.findBySimulationId(simulation.getId()).orElse(null);
        if (contact != null && lead == null) {
            lead = createLead(simulation, contact, now);
        }
        return view(simulation, lead, true);
    }

    // ------------------------------------------------------------------ cotización emitida (código + token)

    @Transactional(readOnly = true)
    public QuoteView get(String code, String token) {
        QuoteSimulation simulation = authorized(code, token);
        return view(simulation, requests.findBySimulationId(simulation.getId()).orElse(null), false);
    }

    /** El cliente deja sus datos después de ver el precio. Una consulta por cotización. */
    @Transactional
    public QuoteView submitLead(String code, String token, ContactRequest request, String ip) {
        QuoteSimulation simulation = authorized(code, token);
        if (requests.findBySimulationId(simulation.getId()).isPresent()) {
            throw ApiException.conflict("LEAD_ALREADY_SENT",
                    "Ya enviaste tus datos para esta cotización. El negocio se va a comunicar con vos.");
        }
        QuoteContext context = context(simulation);
        Contact contact = validate(request, context.country(), "");
        if (!rateLimiter.tryConsume(RateLimiter.Policy.PUBLIC_LEAD_IP, ip)) {
            throw ApiException.tooManyRequests();
        }
        QuoteRequest lead = createLead(simulation, contact, clock.instant());
        return view(simulation, lead, false);
    }

    /** Registra que el cliente abrió WhatsApp (intención de contacto; no es una consulta). */
    @Transactional
    public void whatsappClicked(String code, String token) {
        QuoteSimulation simulation = authorized(code, token);
        simulation.whatsappClicked(clock.instant());
    }

    @Transactional(readOnly = true)
    public QuoteDocuments.Pdf pdf(String code, String token) {
        QuoteSimulation simulation = authorized(code, token);
        return documents.pdf(simulation, requests.findBySimulationId(simulation.getId()).orElse(null));
    }

    // ------------------------------------------------------------------ helpers

    private PublishedCalculator published(String tenantSlug, String calculatorSlug) {
        return calculators.find(tenantSlug, calculatorSlug)
                .orElseThrow(() -> ApiException.notFound("No encontramos este cotizador. Revisá el enlace."));
    }

    /** Código y token válidos y negocio activo. Cualquier falla responde igual (404): no se revela qué falló. */
    private QuoteSimulation authorized(String code, String token) {
        ApiException notFound = ApiException.notFound("No encontramos esta cotización. Revisá el enlace.");
        if (code == null || !CODE.matcher(code.toUpperCase(Locale.ROOT)).matches() || !Tokens.looksValid(token)) {
            throw notFound;
        }
        QuoteSimulation simulation = simulations.findByCode(code.toUpperCase(Locale.ROOT)).orElseThrow(() -> notFound);
        boolean tokenOk = MessageDigest.isEqual(simulation.getAccessToken().getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
        if (!tokenOk || !context(simulation).tenantActive()) {
            throw notFound;
        }
        return simulation;
    }

    private QuoteContext context(QuoteSimulation simulation) {
        return calculators.context(simulation.getTenantId(), simulation.getCalculatorId(),
                simulation.getCalculatorVersionId()).orElseThrow(() -> ApiException.notFound("No encontramos esta cotización."));
    }

    private QuoteRequest createLead(QuoteSimulation simulation, Contact contact, Instant now) {
        PriceBreakdown breakdown = json.read(simulation.getResult(), PriceBreakdown.class);
        QuoteRequest lead = requests.save(new QuoteRequest(simulation, QuoteTexts.summary(breakdown.answered()),
                contact.name(), contact.whatsapp(), contact.email(), contact.comments(), now));
        history.save(new QuoteHistoryEntry(lead, QuoteHistoryEntry.Kind.CREATED, null, QuoteRequest.Status.NUEVA, null,
                null, now));
        notifier.newLead(lead, simulation.getPriceScale());
        return lead;
    }

    QuoteView view(QuoteSimulation simulation, QuoteRequest lead, boolean includeToken) {
        QuoteContext context = context(simulation);
        CalculatorConfig.Commercial commercial = context.config().commercial();
        BusinessCard card = card(simulation.getTenantId(), context.appearance());
        LocalDate today = LocalDate.now(clock.withZone(context.zone()));
        String whatsappUrl = card.whatsapp() == null ? null : QuoteTexts.waUrl(card.whatsapp(),
                QuoteTexts.customerMessage(commercial.whatsappTemplate(), simulation.getCalculatorName(),
                        simulation.getTotal(), simulation.getPriceScale(), simulation.getCode()));
        return new QuoteView(simulation.getCode(), includeToken ? simulation.getAccessToken() : null,
                simulation.getCreatedAt(), simulation.getValidUntil(), simulation.isExpired(today),
                simulation.getCalculatorName(), context.publicUrl(), card, context.appearance().accentColor(),
                json.tree(simulation.getResult()), (int) simulation.getPriceScale(), simulation.getCurrency(),
                simulation.getDisclaimer(), whatsappUrl, commercial.leadCapture().name(),
                new PublicQuoteDtos.RequestStatus(lead != null, lead == null ? null : lead.getCreatedAt()),
                context.showBranding());
    }

    private BusinessCard card(java.util.UUID tenantId, Appearance appearance) {
        return businesses.find(tenantId).map(b -> new BusinessCard(b.name(), b.description(),
                        Boolean.TRUE.equals(appearance.showLogo()) ? b.logoUrl() : null, b.whatsapp(), b.phone(),
                        b.contactEmail(), b.website()))
                .orElseThrow(() -> ApiException.notFound("No encontramos este negocio."));
    }

    private record Contact(String name, String whatsapp, String email, String comments) {
    }

    private static Contact validate(ContactRequest request, String country, String fieldPrefix) {
        if (request.website() != null && !request.website().isBlank()) {
            // Campo trampa: solo lo completan los bots.
            throw ApiException.badRequest("REJECTED", "No pudimos enviar tus datos. Probá de nuevo.");
        }
        if (!request.consent()) {
            throw ApiException.field(fieldPrefix + "consent",
                    "Para enviar la consulta tenés que aceptar que el negocio use estos datos para responderte.");
        }
        String whatsapp = Phones.whatsapp(request.whatsapp(), country).orElseThrow(() -> ApiException.field(
                fieldPrefix + "whatsapp", "Ingresá tu WhatsApp con código de área, por ejemplo 11 2345-6789."));
        return new Contact(clean(request.name()), whatsapp,
                blankToNull(request.email()) == null ? null : request.email().strip().toLowerCase(Locale.ROOT),
                blankToNull(clean(request.comments())));
    }

    /** Quita caracteres de control (salvo saltos de línea en comentarios) y espacios sobrantes. */
    private static String clean(String value) {
        return value == null ? null : value.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String newCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder code = new StringBuilder("COT-");
            for (int i = 0; i < 6; i++) {
                code.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
            }
            if (!simulations.existsByCode(code.toString())) {
                return code.toString();
            }
        }
        throw new IllegalStateException("No se pudo generar un código de cotización único");
    }

}
