package com.climind.saas.businesses;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Logo del negocio: validación por contenido real, almacenamiento fuera de la base y lectura pública. */
class BusinessLogoIT extends IntegrationTest {

    /** PNG mínimo válido (1×1). */
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89};

    private MvcResult upload(Browser browser, String filename, String contentType, byte[] content) throws Exception {
        return browser.perform(multipart("/api/v1/business/logo")
                .file(new MockMultipartFile("file", filename, contentType, content))
                .header("X-XSRF-TOKEN", browser.csrfToken()));
    }

    @Test
    void subirReemplazarLeerYQuitarElLogo() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");

        MvcResult first = upload(ana, "logo.png", "image/png", PNG);
        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        String url = JsonPath.read(first.getResponse().getContentAsString(), "$.logoUrl");
        assertThat(url).startsWith("/api/v1/public/assets/");

        // Lectura pública (sin sesión), con tipo detectado por contenido y caché inmutable.
        MvcResult image = mockMvc.perform(anonymousGet(url)).andReturn();
        assertThat(image.getResponse().getStatus()).isEqualTo(200);
        assertThat(image.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(image.getResponse().getContentAsByteArray()).isEqualTo(PNG);
        assertThat(image.getResponse().getHeader("Cache-Control")).contains("immutable");

        // El binario no está en la base: solo la referencia.
        assertThat(jdbc.queryForObject("select storage_key from business_assets", String.class))
                .matches("tenants/[0-9a-f-]+/logo-[0-9a-f-]+\\.png");
        assertThat(ana.get("/api/v1/business/profile").getResponse().getContentAsString()).contains(url);

        // Reemplazar: el anterior deja de existir.
        String second = JsonPath.read(upload(ana, "nuevo.png", "image/png", PNG).getResponse().getContentAsString(),
                "$.logoUrl");
        assertThat(second).isNotEqualTo(url);
        assertThat(mockMvc.perform(anonymousGet(url)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from business_assets", Long.class)).isEqualTo(1);

        // Quitar.
        MvcResult removed = ana.perform(delete("/api/v1/business/logo").header("X-XSRF-TOKEN", ana.csrfToken()));
        assertThat(removed.getResponse().getStatus()).isEqualTo(204);
        assertThat(mockMvc.perform(anonymousGet(second)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select logo_asset_id from business_profiles", String.class)).isNull();
    }

    @Test
    void rechazaArchivosQueNoSonImagenesAunqueDiganSerlo() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");

        MvcResult fake = upload(ana, "logo.png", "image/png", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        assertThat(fake.getResponse().getStatus()).isEqualTo(400);
        assertThat(fake.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("PNG, JPG o WebP");

        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        assertThat(upload(ana, "logo.svg", "image/svg+xml", svg).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from business_assets", Long.class)).isZero();
    }

    @Test
    void rechazaArchivosDemasiadoGrandes() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        byte[] big = Arrays.copyOf(PNG, 2 * 1024 * 1024 + 1);
        assertThat(upload(ana, "logo.png", "image/png", big).getResponse().getStatus()).isEqualTo(413);
    }

    @Test
    void sinCsrfNoSePuedeSubir() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        MvcResult result = ana.perform(multipart("/api/v1/business/logo")
                .file(new MockMultipartFile("file", "logo.png", "image/png", PNG)));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void assetInexistenteEs404() throws Exception {
        assertThat(mockMvc.perform(anonymousGet("/api/v1/public/assets/0190d7a8-0000-7000-8000-000000000000"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
