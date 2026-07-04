package io.liparakis.chunkis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and utilities for Chunkis core.
 * Loader-agnostic.
 */
public class Chunkis {

    /**
     * The common identifier used across all modules.
     */
    public static final String MOD_ID = "chunkis";
    /**
     * The shared SLF4J logger instance for the core module.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
}
