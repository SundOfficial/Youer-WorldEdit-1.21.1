import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.extension.platform.PlatformManager;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Standalone synthetic probe; no Minecraft server, worlds or native registries. */
public final class BlockStateMemoryProbe {
    private static final List<BlockType> RETAINED = new ArrayList<>();

    private static BlockType type(String id, int... counts) {
        Map<String, Property<?>> properties = new LinkedHashMap<>();
        for (int i = 0; i < counts.length; i++) {
            String name = "p" + i;
            properties.put(name, new IntegerProperty(name, IntStream.range(0, counts[i]).boxed().toList()));
        }
        return new BlockType(id) {
            @Override
            public Map<String, ? extends Property<?>> getPropertyMap() {
                return properties;
            }
        };
    }

    private static long usedAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // Supply only the watchdog capability. Reflection is confined to this isolated probe.
        PlatformManager manager = WorldEdit.getInstance().getPlatformManager();
        Field preferencesField = PlatformManager.class.getDeclaredField("preferences");
        preferencesField.setAccessible(true);
        Map<Capability, Platform> preferences = (Map<Capability, Platform>) preferencesField.get(manager);
        Platform platform = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
            new Class<?>[] { Platform.class }, (proxy, method, arguments) -> {
                if (method.getName().equals("getWatchdog")) {
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        preferences.put(Capability.GAME_HOOKS, platform);
        type("probe:warmup", 2, 3, 4).getAllStates();
        RETAINED.add(type("probe:24576", 8, 8, 8, 8, 6));
        RETAINED.add(type("probe:22400", 10, 10, 8, 7, 4));
        long before = usedAfterGc();
        long started = System.nanoTime();
        long stateCount = 0;
        for (BlockType type : RETAINED) {
            stateCount += type.getAllStates().size();
        }
        long generationNanos = System.nanoTime() - started;
        long retainedBytes = usedAfterGc() - before;
        // Check a deterministic fingerprint of every property combination and its order.
        long fingerprint = 1;
        for (BlockType type : RETAINED) {
            for (BlockState state : type.getAllStates()) {
                for (Object value : state.getStates().values()) {
                    fingerprint = 31 * fingerprint + value.hashCode();
                }
            }
        }
        System.out.printf("RESULT states=%d retainedBytes=%d generationNanos=%d fingerprint=%d source=%s%n",
            stateCount, retainedBytes, generationNanos, fingerprint,
            BlockState.class.getProtectionDomain().getCodeSource().getLocation());
        // WorldEdit starts background threads; this standalone probe owns its JVM.
        System.exit(0);
    }
}
