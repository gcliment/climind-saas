package com.climind.saas.assistant;

import com.climind.saas.shared.web.StaticPages;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lo que sabe el asistente: sus instrucciones, la guía del panel y el texto de los artículos de la ayuda
 * (una sola fuente: las mismas páginas que lee el usuario). También indexa las preguntas frecuentes para
 * responder sin IA cuando no hay clave de API o el servicio falla.
 */
@Component
public class HelpKnowledge {

    /** Artículos de la ayuda, en el orden en que se presentan. */
    static final List<String> ARTICLES = List.of("primeros-pasos", "crear-cotizador", "precios-y-reglas",
            "publicar-y-compartir", "consultas", "planes-y-pagos", "cuenta-y-datos", "preguntas-frecuentes");

    private static final Pattern ARTICLE = Pattern.compile("<article class=\"help-article\">(.*?)</article>",
            Pattern.DOTALL);
    private static final Pattern FAQ_ITEM = Pattern.compile(
            "<details class=\"faq-item\">\\s*<summary>(.*?)</summary>(.*?)</details>", Pattern.DOTALL);
    private static final Set<String> STOPWORDS = Set.of("como", "cómo", "que", "qué", "para", "por", "con", "los",
            "las", "del", "una", "uno", "unos", "unas", "mis", "mi", "tus", "tu", "el", "la", "de", "en", "y", "o",
            "a", "se", "es", "lo", "le", "me", "no", "si", "sí", "al", "hay", "puedo", "quiero", "hago", "donde",
            "dónde", "cuando", "cuándo", "esta", "está", "este", "esto", "eso", "son", "ser", "tengo", "hacer");

    private final StaticPages pages;
    private volatile String cachedDocumentation;
    private volatile List<Faq> cachedFaqs;

    public HelpKnowledge(StaticPages pages) {
        this.pages = pages;
    }

    /** Pregunta frecuente con su respuesta en texto plano. */
    public record Faq(String question, String answer) {
    }

    /** Instrucciones del asistente (rol, tono y límites). */
    public String instructions() {
        return resource("assistant/instrucciones.md");
    }

    /**
     * Guía del panel + artículos de la ayuda, como texto. Se arma una vez: cambia solo con un deploy y así el
     * prefijo del prompt queda idéntico entre pedidos (se aprovecha la caché del prompt).
     */
    public String documentation() {
        String doc = cachedDocumentation;
        if (doc == null) {
            StringBuilder out = new StringBuilder(resource("assistant/guia-del-panel.md")).append("\n\n");
            out.append("DOCUMENTACIÓN DEL CENTRO DE AYUDA\n");
            for (String slug : ARTICLES) {
                articleText(slug).ifPresent(text -> out.append("\n=== /ayuda/").append(slug).append(" ===\n")
                        .append(text).append('\n'));
            }
            doc = out.toString();
            cachedDocumentation = doc;
        }
        return doc;
    }

    /** Preguntas frecuentes de /ayuda/preguntas-frecuentes. */
    public List<Faq> faqs() {
        List<Faq> faqs = cachedFaqs;
        if (faqs == null) {
            List<Faq> found = new ArrayList<>();
            pages.read("ayuda/preguntas-frecuentes.html").ifPresent(html -> {
                Matcher m = FAQ_ITEM.matcher(html);
                while (m.find()) {
                    found.add(new Faq(toText(m.group(1)), toText(m.group(2))));
                }
            });
            faqs = List.copyOf(found);
            cachedFaqs = faqs;
        }
        return faqs;
    }

    /** Las preguntas frecuentes que mejor coinciden con la pregunta (por palabras en común). */
    public List<Faq> search(String question, int limit) {
        Set<String> words = words(question);
        if (words.isEmpty()) {
            return List.of();
        }
        record Scored(Faq faq, double score) {
        }
        return faqs().stream()
                .map(faq -> {
                    Set<String> q = words(faq.question());
                    Set<String> a = words(faq.answer());
                    double score = 0;
                    for (String w : words) {
                        if (q.contains(w)) {
                            score += 3;
                        } else if (a.contains(w)) {
                            score += 1;
                        }
                    }
                    return new Scored(faq, score);
                })
                .filter(s -> s.score() >= 3)
                .sorted((x, y) -> Double.compare(y.score(), x.score()))
                .limit(limit)
                .map(Scored::faq)
                .toList();
    }

    Optional<String> articleText(String slug) {
        return pages.read("ayuda/" + slug + ".html").map(html -> {
            Matcher m = ARTICLE.matcher(html);
            return toText(m.find() ? m.group(1) : html);
        });
    }

    /** HTML → texto legible: títulos y elementos de lista en líneas propias, sin etiquetas. */
    static String toText(String html) {
        String text = html
                .replaceAll("(?is)<(script|style|nav)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<h[1-3][^>]*>", "\n\n# ")
                .replaceAll("(?i)<summary[^>]*>", "\n\nP: ")
                .replaceAll("(?i)<li[^>]*>", "\n- ")
                .replaceAll("(?i)<(p|tr|div|ol|ul|section|details)[^>]*>", "\n")
                .replaceAll("(?i)</t[dh]>", " | ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    static Set<String> words(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        Set<String> out = new java.util.HashSet<>();
        for (String w : normalized.split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOPWORDS.contains(w)) {
                // Raíz simple (6 letras): «precios» y «precio», «instalar» e «instalación» cuentan igual.
                out.add(w.length() > 6 ? w.substring(0, 6) : w);
            }
        }
        return out;
    }

    private static String resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Falta el recurso " + path, e);
        }
    }
}
