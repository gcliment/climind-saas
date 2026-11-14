package com.climind.saas.storage;

import com.climind.saas.shared.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/** Archivos en un volumen persistente (EasyPanel: montaje en /app/storage). */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    /** Solo claves generadas por la app: evita path traversal aunque una clave llegara manipulada. */
    private static final Pattern SAFE_KEY = Pattern.compile("^[a-z0-9-]+(/[a-z0-9-]+)*\\.[a-z0-9]{2,5}$");

    private final Path root;

    public LocalFileStorage(AppProperties properties) {
        this.root = Path.of(properties.getStorage().getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear la carpeta de almacenamiento " + root, e);
        }
        log.info("Almacenamiento de archivos en {}", root);
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el archivo", e);
        }
    }

    @Override
    public Optional<Resource> get(String key) {
        Path path = resolve(key);
        return Files.isRegularFile(path) ? Optional.of(new FileSystemResource(path)) : Optional.empty();
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("No se pudo borrar el archivo {}", key, e);
        }
    }

    private Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Clave de archivo inválida");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Clave de archivo inválida");
        }
        return path;
    }
}
