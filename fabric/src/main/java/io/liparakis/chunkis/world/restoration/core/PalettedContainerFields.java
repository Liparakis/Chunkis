package io.liparakis.chunkis.world.restoration.core;

import io.liparakis.chunkis.Chunkis;
import java.lang.reflect.Field;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;

/**
 * Reflective access to a {@link PalettedContainer}'s current data holder, its raw
 * {@link PaletteStorage} and its {@link Palette}.
 *
 * <p>Fields are resolved once, trying the named, intermediary and obfuscated names. When none
 * resolve, {@link #AVAILABLE} is {@code false} and callers must use the public container API.</p>
 */
public final class PalettedContainerFields {

    /**
     * {@code PalettedContainer.data}, or {@code null} when unavailable.
     */
    static final Field DATA;

    /**
     * {@code PalettedContainer.Data.storage}, or {@code null} when unavailable.
     */
    static final Field STORAGE;

    /**
     * {@code PalettedContainer.Data.palette}, or {@code null} when unavailable.
     */
    static final Field PALETTE;

    /**
     * Whether all three fields resolved.
     */
    public static final boolean AVAILABLE;

    static {
        Field data = null;
        Field storage = null;
        Field palette = null;
        try {
            data = findField(PalettedContainer.class, "data", "field_34560", "b");
            storage = findField(data.getType(), "storage", "comp_118", "b");
            palette = findField(data.getType(), "palette", "comp_119", "c");
        } catch (final ReflectiveOperationException | RuntimeException e) {
            Chunkis.LOGGER.warn("Chunkis: PalettedContainer raw access unavailable; using slower public paths", e);
            data = null;
            storage = null;
            palette = null;
        }
        DATA = data;
        STORAGE = storage;
        PALETTE = palette;
        AVAILABLE = data != null;
    }

    /**
     * Static holder; not instantiable.
     */
    private PalettedContainerFields() {
    }

    /**
     * Returns the container's current data holder; it is replaced whenever the palette resizes.
     *
     * @param container block-state container
     * @return data holder
     */
    public static Object data(final PalettedContainer<?> container) {
        return read(DATA, container);
    }

    /**
     * Returns the raw storage of a data holder.
     *
     * @param data data holder from {@link #data}
     * @return raw palette-index storage
     */
    public static PaletteStorage storage(final Object data) {
        return (PaletteStorage) read(STORAGE, data);
    }

    /**
     * Returns the palette of a data holder.
     *
     * @param data data holder from {@link #data}
     * @param <T>  element type
     * @return palette
     */
    @SuppressWarnings("unchecked")
    public static <T> Palette<T> palette(final Object data) {
        return (Palette<T>) read(PALETTE, data);
    }

    /**
     * Reads a resolved field.
     *
     * @param field  field to read
     * @param target owning object
     * @return field value
     */
    private static Object read(final Field field, final Object target) {
        try {
            return field.get(target);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException("Chunkis: failed reading field " + field.getName(), e);
        }
    }

    /**
     * Finds one declared field using candidate names across mapping namespaces.
     *
     * @param owner          declaring class
     * @param candidateNames names to try in order
     * @return the accessible field
     * @throws NoSuchFieldException when no candidate exists
     */
    private static Field findField(final Class<?> owner, final String... candidateNames) throws NoSuchFieldException {
        for (final String candidateName : candidateNames) {
            try {
                final Field field = owner.getDeclaredField(candidateName);
                field.setAccessible(true);
                return field;
            } catch (final NoSuchFieldException ignored) {
                // Try the next namespace name.
            }
        }
        throw new NoSuchFieldException(owner.getName() + " [" + String.join(", ", candidateNames) + "]");
    }
}
