package com.climind.saas.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.Objects;
import java.util.UUID;

/**
 * Entidad con UUID asignado por la aplicación ({@link com.climind.saas.shared.util.Ids}).
 * Implementa {@link Persistable} para que {@code save()} haga INSERT directo en lugar de SELECT + merge.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    protected AssignedIdEntity() {
    }

    protected AssignedIdEntity(UUID id) {
        this.id = Objects.requireNonNull(id);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || org.hibernate.Hibernate.getClass(this) != org.hibernate.Hibernate.getClass(o)) {
            return false;
        }
        return id != null && id.equals(((AssignedIdEntity) o).id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
