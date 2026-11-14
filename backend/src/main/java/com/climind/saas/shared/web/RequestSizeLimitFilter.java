package com.climind.saas.shared.web;

import com.climind.saas.shared.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limita el tamaño de los cuerpos JSON de la API (incluidos los enviados con transfer-encoding chunked).
 * Los multipart (logotipos) tienen su propio límite en {@code spring.servlet.multipart.*}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final int maxBytes;

    public RequestSizeLimitFilter(AppProperties properties) {
        this.maxBytes = properties.getSecurity().getMaxJsonBodyBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return !request.getRequestURI().startsWith("/api/")
                || (contentType != null && contentType.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"La solicitud es demasiado grande.\"}");
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final int maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, int maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final int maxBytes;
        private long read;

        LimitedInputStream(ServletInputStream delegate, int maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = delegate.read(buffer, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > maxBytes) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
