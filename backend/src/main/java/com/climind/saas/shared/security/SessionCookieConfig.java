package com.climind.saas.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Cookie de sesión de Spring Session, configurada explícitamente.
 * <p>
 * Con Spring Boot 4 las propiedades {@code server.servlet.session.cookie.*} NO se aplican a la cookie de
 * Spring Session (se emitía como {@code SESSION}, sin HttpOnly ni SameSite). Este bean lo fija y
 * {@code AuthFlowIT} lo verifica.
 */
@Configuration
public class SessionCookieConfig {

    public static final String COOKIE_NAME = "CLIMIND_SESSION";

    @Bean
    public CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(COOKIE_NAME);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        return serializer;
    }
}
