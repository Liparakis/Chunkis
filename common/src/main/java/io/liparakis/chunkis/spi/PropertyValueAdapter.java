package io.liparakis.chunkis.spi;

/**
 * Optional block-state adapter extension that can expose the current property
 * value directly from a state.
 *
 * <p>
 * This lets hot serialization code perform value-to-index lookup against
 * precomputed property metadata without routing back through a generic
 * adapter-managed cache.
 *
 * @param <S> block-state type
 * @param <P> property type
 */
public interface PropertyValueAdapter<S, P> {

    /**
     * Returns the current value for the given property on the supplied state.
     */
    Object getPropertyValue(S state, P property);
}
