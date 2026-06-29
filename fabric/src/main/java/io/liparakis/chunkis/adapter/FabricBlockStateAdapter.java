package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.PropertyValueAdapter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

/**
 * High-performance Fabric implementation of BlockStateAdapter with aggressive caching.
 *
 * <p>
 * This adapter optimizes for Minecraft's immutable block state system where:
 * <ul>
 * <li>Block properties are defined at registration time and never change</li>
 * <li>Property values are finite and immutable</li>
 * <li>Block states are queried millions of times per second in chunk operations</li>
 * </ul>
 *
 * <p>
 * Thread-safe for concurrent access.
 *
 * @author Liparakis
 * @version 1.1
 */
public final class FabricBlockStateAdapter
        implements BlockStateAdapter<Block, BlockState, Property<?>>, PropertyValueAdapter<BlockState, Property<?>> {

    // Shared immutable sentinels to avoid allocation for blocks with no properties.
    private static final List<Property<?>> EMPTY_PROPERTIES = Collections.emptyList();
    private static final List<Object> EMPTY_VALUES = Collections.emptyList();
    private static final java.lang.reflect.Method GET_VALUES_METHOD = resolveGetValuesMethod();
    // Caches for immutable block metadata — safe to retain indefinitely since
    // block properties and their values are fixed at registration time.
    private final Map<Block, List<Property<?>>> blockPropertiesCache = new ConcurrentHashMap<>(256);
    private final Map<Property<?>, List<Object>> propertyValuesCache = new ConcurrentHashMap<>(512);

    // -------------------------------------------------------------------------
    // Reflection bootstrap — resolves Property.getValues() once at class load.
    // This handles remapped method names across Minecraft mapping sets
    // (e.g., intermediary vs. named) and return type changes (List vs. Collection).
    // -------------------------------------------------------------------------
    private final Map<Property<?>, Map<Object, Integer>> valueIndexCache = new ConcurrentHashMap<>(512);

    /**
     * Attempts to resolve {@code Property.getValues()} by name first,
     * then falls back to scanning public methods by signature.
     *
     * @return the resolved Method, or null if resolution fails entirely
     */
    private static java.lang.reflect.Method resolveGetValuesMethod() {
        try {
            // Primary: resolve by name (works in dev/named mappings)
            return Property.class.getMethod("getValues");
        } catch (final NoSuchMethodException e) {
            // Fallback: scan by signature (handles intermediary/production mappings)
            return scanForValuesMethod();
        }
    }

    /**
     * Scans all public methods on {@link Property} to find one that matches
     * the expected signature of {@code getValues()}: no parameters, returns a Collection.
     *
     * @return the matching Method, or null if none found
     */
    private static java.lang.reflect.Method scanForValuesMethod() {
        for (final java.lang.reflect.Method method : Property.class.getMethods()) {
            if (isValuesMethod(method)) {
                return method;
            }
        }
        return null;
    }

    /**
     * Returns true if the given method matches the expected signature of
     * {@code getValues()}: zero parameters and a Collection return type,
     * excluding known false-positive methods.
     *
     * @param method the method to evaluate
     * @return true if the method is a candidate for getValues()
     */
    private static boolean isValuesMethod(final java.lang.reflect.Method method) {
        return Collection.class.isAssignableFrom(method.getReturnType())
                && method.getParameterCount() == 0
                && !isExcludedReturnType(method.getReturnType());
    }

    /**
     * Returns true if the return type is a known false-positive that should
     * be excluded during method scanning (e.g., {@code getName()}, {@code getType()}).
     *
     * @param returnType the return type to check
     * @return true if the type should be excluded from matching
     */
    private static boolean isExcludedReturnType(final Class<?> returnType) {
        return returnType.equals(Class.class)
                || returnType.equals(String.class)
                || returnType.equals(Optional.class);
    }

    /**
     * Invokes {@code property.getValues()} via the resolved reflective method,
     * falling back to a direct call if reflection throws.
     *
     * <p>
     * The fallback avoids a hard failure during class initialization and provides
     * resilience against unexpected mapping environments.
     *
     * @param property the property to query
     * @return the raw collection of values
     */
    private static Collection<?> safeGetValues(final Property<?> property) {
        try {
            return invokeGetValues(property);
        } catch (final Exception e) {
            // Fallback to direct call if reflection fails (e.g., access restrictions)
            return property.getValues();
        }
    }

    /**
     * Reflectively invokes {@code Property.getValues()} using the pre-resolved method.
     * Falls back to a direct call if the method could not be resolved at class load.
     *
     * @param property the property to query
     * @return the raw collection of allowed values
     * @throws Exception if reflective invocation fails
     */
    private static Collection<?> invokeGetValues(final Property<?> property) throws Exception {
        if (GET_VALUES_METHOD != null) {
            return (Collection<?>) GET_VALUES_METHOD.invoke(property);
        }
        // Direct call fallback — used when reflection resolution failed entirely
        return property.getValues();
    }

    /**
     * Returns true if the given index falls outside the bounds of the values list.
     *
     * @param index  the index to check
     * @param values the list to check against
     * @return true if index is negative or >= values.size()
     */
    private static boolean isOutOfBounds(final int index, final List<?> values) {
        return index < 0 || index >= values.size();
    }

    /**
     * Applies the given value to the given property on the given state.
     *
     * <p>
     * The unchecked cast is safe because {@code value} originates from
     * {@code property.getValues()}, guaranteeing type compatibility.
     * The {@code @SuppressWarnings} scope is intentionally kept to this
     * single method to minimize the blast radius of the suppression.
     *
     * @param state    the block state to modify
     * @param property the property to set
     * @param value    the value to apply, sourced from the property's own value list
     * @return a new BlockState with the property applied
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyPropertyValue(
            final BlockState state,
            final Property<?> property,
            final Object value) {
        return state.with((Property) property, (Comparable) value);
    }

    /**
     * Extracts the block from a block state.
     *
     * @param state the block state
     * @return the block
     * @throws NullPointerException if state is null
     */
    @Override
    public Block getBlock(final BlockState state) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        return state.getBlock();
    }

    /**
     * Returns an immutable list of properties for the given block with caching.
     *
     * <p>
     * Block properties are defined at registration and never change,
     * making them safe to cache indefinitely.
     *
     * @param block the block
     * @return unmodifiable list of properties
     * @throws NullPointerException if block is null
     */
    @Override
    public List<Property<?>> getProperties(final Block block) {
        Objects.requireNonNull(block, "Block cannot be null");
        return blockPropertiesCache.computeIfAbsent(block, this::resolveProperties);
    }

    /**
     * Returns the name of a property.
     *
     * @param property the property
     * @return the property name
     * @throws NullPointerException if property is null
     */
    @Override
    public String getPropertyName(final Property<?> property) {
        Objects.requireNonNull(property, "Property cannot be null");
        return property.getName();
    }

    /**
     * Returns an immutable list of possible values for a property with caching.
     *
     * <p>
     * Uses reflection to handle method signature differences across Minecraft versions,
     * with a direct-call fallback if reflection fails.
     *
     * @param property the property
     * @return unmodifiable list of values
     * @throws NullPointerException if property is null
     */
    @Override
    public List<Object> getPropertyValues(final Property<?> property) {
        Objects.requireNonNull(property, "Property cannot be null");
        return propertyValuesCache.computeIfAbsent(property, this::resolvePropertyValues);
    }

    /**
     * Gets the index of the current value of a property in the block state.
     *
     * <p>
     * Optimized with O(1) index lookup using a cached value-to-index mapping.
     *
     * @param state    the block state
     * @param property the property to query
     * @return the index of the current value, or -1 if not found
     * @throws NullPointerException if state or property is null
     */
    @Override
    public int getValueIndex(final BlockState state, final Property<?> property) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        Objects.requireNonNull(property, "Property cannot be null");
        final Comparable<?> currentValue = state.get(property);
        return getOrCreateIndexMap(property).getOrDefault(currentValue, -1);
    }

    @Override
    public Object getPropertyValue(final BlockState state, final Property<?> property) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        Objects.requireNonNull(property, "Property cannot be null");
        return state.get(property);
    }

    /**
     * Creates a new block state with the specified property value.
     *
     * <p>
     * Uses direct indexed access into the cached values list, avoiding
     * ArrayList creation and linear indexOf searches on the hot path.
     *
     * @param state      the original block state
     * @param property   the property to modify
     * @param valueIndex the index of the desired value
     * @return new block state with the property set, or original state if index is invalid
     * @throws NullPointerException if state or property is null
     */
    @Override
    public BlockState withProperty(
            final BlockState state,
            final Property<?> property,
            final int valueIndex) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        Objects.requireNonNull(property, "Property cannot be null");

        final List<Object> values = getPropertyValues(property);
        if (isOutOfBounds(valueIndex, values)) {
            return state;
        }

        return applyPropertyValue(state, property, values.get(valueIndex));
    }

    /**
     * Returns the default state for a block.
     *
     * @param block the block
     * @return the default block state
     * @throws NullPointerException if block is null
     */
    @Override
    public BlockState getDefaultState(final Block block) {
        Objects.requireNonNull(block, "Block cannot be null");
        return block.getDefaultState();
    }

    /**
     * Resolves the property list for the given block, returning the shared
     * empty sentinel if the block has no properties.
     *
     * @param block the block to inspect
     * @return immutable list of properties, never null
     */
    private List<Property<?>> resolveProperties(final Block block) {
        final Collection<Property<?>> properties = block.getStateManager().getProperties();
        // Fast path for the common case of stateless blocks (e.g., stone)
        return properties.isEmpty() ? EMPTY_PROPERTIES : List.copyOf(properties);
    }

    /**
     * Resolves the value list for the given property using reflection with
     * a direct-call fallback. Returns the shared empty sentinel for properties
     * with no values.
     *
     * @param property the property to inspect
     * @return immutable list of values, never null
     */
    private List<Object> resolvePropertyValues(final Property<?> property) {
        final Collection<?> values = safeGetValues(property);
        return values.isEmpty() ? EMPTY_VALUES : List.copyOf(values);
    }

    /**
     * Gets or creates an O(1) value-to-index mapping for the given property.
     *
     * <p>
     * This cache allows index lookups to avoid linear scans of the values list,
     * critical for high-frequency block state queries during chunk processing.
     *
     * <p>
     * Package-private for testing visibility.
     *
     * @param property the property to build or retrieve an index map for
     * @return unmodifiable map of value → index
     */
    Map<Object, Integer> getOrCreateIndexMap(final Property<?> property) {
        return valueIndexCache.computeIfAbsent(property, this::buildIndexMap);
    }

    /**
     * Builds a value-to-index map for the given property by iterating
     * over its cached values list.
     *
     * @param property the property to index
     * @return unmodifiable map of value → index
     */
    private Map<Object, Integer> buildIndexMap(final Property<?> property) {
        final List<Object> values = getPropertyValues(property);
        // Pre-size to avoid rehashing
        final Map<Object, Integer> indexMap = new HashMap<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            indexMap.put(values.get(i), i);
        }
        return Collections.unmodifiableMap(indexMap);
    }
}
