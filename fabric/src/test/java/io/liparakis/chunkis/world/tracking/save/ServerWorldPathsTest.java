package io.liparakis.chunkis.world.tracking.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Worker lookups must leave the native non-thread-safe resource cache untouched. */
class ServerWorldPathsTest {
    /** Initializes the native save-access dependencies used by this integration-level unit test. */
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** Concurrent root reads remain independent of unrelated native resource-cache writes. */
    @Test
    void workerRootsDoNotPopulateNativeCache(@TempDir final Path directory) throws Exception {
        try (var access = LevelStorageSource.createDefault(directory).createAccess("world");
                var workers = Executors.newFixedThreadPool(4)) {
            final Field field = LevelStorageSource.LevelStorageAccess.class.getDeclaredField("resources");
            field.setAccessible(true);
            final Map<?, ?> cache = (Map<?, ?>) field.get(access);
            assertFalse(cache.containsKey(LevelResource.ROOT));
            final Path expected = access.getLevelDirectory().resourcePath(LevelResource.ROOT);
            final List<Callable<Path>> reads = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                reads.add(() -> {
                    for (int read = 0; read < 10_000; read++) {
                        assertEquals(expected, ServerWorldPaths.root(access));
                    }
                    return ServerWorldPaths.root(access);
                });
            }
            final var results = reads.stream().map(workers::submit).toList();
            for (int i = 0; i < 1_000; i++) {
                access.getLevelPath(new LevelResource("other-" + i));
            }
            for (final var result : results) {
                assertEquals(expected, result.get(10, TimeUnit.SECONDS));
            }
            assertFalse(cache.containsKey(LevelResource.ROOT));
        }
    }
}
