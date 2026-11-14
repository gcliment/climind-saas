package com.climind.saas.storage;

import org.springframework.core.io.Resource;

import java.util.Optional;

/**
 * Almacenamiento de archivos fuera de PostgreSQL. Implementación inicial: volumen local
 * ({@link LocalFileStorage}); preparada para un adaptador compatible con S3 sin cambiar a los llamadores.
 * Las claves las genera la aplicación (nunca el usuario): {@code tenants/<tenantId>/<uuid>.<ext>}.
 */
public interface FileStorage {

    void put(String key, byte[] content);

    Optional<Resource> get(String key);

    void delete(String key);
}
