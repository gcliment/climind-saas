package com.climind.saas.quotes;

import com.climind.saas.shared.util.Tokens;
import com.climind.saas.shared.web.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;

/**
 * API del cotizador público (sin sesión ni CSRF: no hay cookies de autenticación que abusar). Protegida con
 * límites por IP, tamaño máximo de request y validación estricta. La cotización emitida se lee con el
 * header {@code X-Quote-Token} (el token nunca viaja en la URL, así no queda en historiales ni registros).
 */
@Tag(name = "Público", description = "Cotizador público para los clientes de cada negocio")
@RestController
@RequestMapping("/api/v1/public")
public class PublicQuoteController {

    static final String TOKEN_HEADER = "X-Quote-Token";
    /** Cookie anónima y efímera solo para no duplicar cotizaciones al recalcular. No identifica personas. */
    static final String VISITOR_COOKIE = "cotiza_vid";

    private final PublicQuoteService service;
    private final ClientIpResolver ipResolver;
    private final boolean secureCookies;

    public PublicQuoteController(PublicQuoteService service, ClientIpResolver ipResolver,
                                 @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookies) {
        this.service = service;
        this.ipResolver = ipResolver;
        this.secureCookies = secureCookies;
    }

    @Operation(summary = "Formulario de un cotizador publicado (sin precios ni reglas)")
    @GetMapping("/c/{tenantSlug}/{calculatorSlug}")
    public ResponseEntity<PublicQuoteDtos.PublicCalculator> calculator(@PathVariable String tenantSlug,
                                                                       @PathVariable String calculatorSlug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body(service.calculator(tenantSlug, calculatorSlug));
    }

    @Operation(summary = "Calcular y guardar una cotización (el importe lo calcula el servidor)")
    @PostMapping("/c/{tenantSlug}/{calculatorSlug}/quotes")
    public ResponseEntity<PublicQuoteDtos.QuoteView> quote(@PathVariable String tenantSlug,
                                                           @PathVariable String calculatorSlug,
                                                           @Valid @RequestBody PublicQuoteDtos.CreateQuoteRequest request,
                                                           HttpServletRequest http, HttpServletResponse response) {
        String visitor = visitorKey(http);
        if (visitor == null) {
            visitor = Tokens.newToken();
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(VISITOR_COOKIE, visitor)
                    .path("/api/v1/public/").httpOnly(true).secure(secureCookies).sameSite("Lax")
                    .maxAge(Duration.ofDays(1)).build().toString());
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(tenantSlug, calculatorSlug, request, visitor, ipResolver.resolve(http)));
    }

    @Operation(summary = "Ver una cotización emitida (código + token)")
    @GetMapping("/quotes/{code}")
    public ResponseEntity<PublicQuoteDtos.QuoteView> get(@PathVariable String code,
                                                         @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(code, token));
    }

    @Operation(summary = "Dejar los datos de contacto para que el negocio responda (una vez por cotización)")
    @PostMapping("/quotes/{code}/request")
    public ResponseEntity<PublicQuoteDtos.QuoteView> request(@PathVariable String code,
                                                             @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                                             @Valid @RequestBody PublicQuoteDtos.ContactRequest request,
                                                             HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.submitLead(code, token, request, ipResolver.resolve(http)));
    }

    @Operation(summary = "Registrar que el cliente abrió WhatsApp desde la cotización")
    @PostMapping("/quotes/{code}/whatsapp")
    public ResponseEntity<Void> whatsapp(@PathVariable String code,
                                         @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        service.whatsappClicked(code, token);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Descargar la cotización en PDF")
    @GetMapping("/quotes/{code}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String code,
                                      @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        QuoteDocuments.Pdf pdf = service.pdf(code, token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.filename()).build().toString())
                .body(pdf.content());
    }

    private static String visitorKey(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies).filter(c -> VISITOR_COOKIE.equals(c.getName()))
                .map(Cookie::getValue).filter(Tokens::looksValid).findFirst().orElse(null);
    }
}
