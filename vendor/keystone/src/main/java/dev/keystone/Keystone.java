package dev.keystone;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The plugin this copy of Keystone belongs to.
 *
 * <p>Every plugin shades and relocates its own Keystone, so this class is per plugin, never
 * shared. {@link #init} must be the first thing the plugin does (its {@code onLoad}): everything
 * else in Keystone reads the plugin from here instead of taking it as a parameter.
 */
public final class Keystone {

    private static volatile JavaPlugin owner;
    private static volatile File jarFile;

    private Keystone() {}

    public static void init(JavaPlugin plugin) {
        owner = plugin;
        jarFile = null;
    }

    /** The owning plugin. Throws until {@link #init} has run. */
    public static JavaPlugin plugin() {
        JavaPlugin plugin = owner;
        if (plugin == null) {
            throw new IllegalStateException("Keystone.init(plugin) has not been called");
        }
        return plugin;
    }

    public static boolean isInitialized() {
        return owner != null;
    }

    public static String name() {
        return plugin().getName();
    }

    /**
     * Version from plugin.yml. {@code getDescription()} is deprecated on Paper but exists on every
     * server version these plugins still run on.
     */
    @SuppressWarnings("deprecation")
    public static String version() {
        return plugin().getDescription().getVersion();
    }

    public static File dataFolder() {
        return plugin().getDataFolder();
    }

    /** The plugin's own jar ({@code JavaPlugin#getFile} is protected). */
    public static File jarFile() {
        File file = jarFile;
        if (file != null) {
            return file;
        }
        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(JavaPlugin.class, MethodHandles.lookup());
            MethodHandle getter =
                    lookup.findVirtual(
                            JavaPlugin.class, "getFile", MethodType.methodType(File.class));
            file = (File) getter.invoke(plugin());
        } catch (Throwable t) {
            throw new IllegalStateException("cannot resolve the plugin jar", t);
        }
        jarFile = file;
        return file;
    }

    /**
     * Opens a resource inside the plugin jar, bypassing the JVM's jar cache so a replaced jar is
     * never read stale. Null when the resource does not exist.
     */
    public static InputStream resource(String path) {
        return plugin().getResource(path.replace('\\', '/'));
    }

    /** Reads a jar resource as UTF-8 text, or null when absent. */
    public static String resourceText(String path) {
        try (InputStream input = resource(path)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a jar resource as bytes, or null when absent. */
    public static byte[] resourceBytes(String path) {
        try (InputStream input = resource(path)) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@link #releaseResource(String, boolean)} without replacing an existing file. */
    public static File releaseResource(String path) {
        return releaseResource(path, false);
    }

    /**
     * Copies a jar resource into the data folder when the target does not exist yet, or always
     * when {@code replace} is set. Returns the target file either way. Unlike {@code saveResource}
     * it does not log a warning for an existing file.
     */
    public static File releaseResource(String path, boolean replace) {
        File target = new File(dataFolder(), path);
        if (target.exists() && !replace) {
            return target;
        }
        try (InputStream input = resource(path)) {
            if (input == null) {
                throw new IllegalArgumentException("资源 " + path + " 不在插件 jar 中");
            }
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }
}
