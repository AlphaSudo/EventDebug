package io.eventlens.core.plugin;

import io.eventlens.spi.EventSourcePlugin;
import io.eventlens.spi.ReducerPlugin;
import io.eventlens.spi.StreamAdapterPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * Discovers plugins from classpath (ServiceLoader) and external JARs.
 */
public class PluginDiscovery {

    private static final Logger log = LoggerFactory.getLogger(PluginDiscovery.class);

    /**
     * Discover all plugins from classpath using ServiceLoader.
     */
    public DiscoveryResult discoverFromClasspath() {
        List<EventSourcePlugin> sources = new ArrayList<>();
        List<StreamAdapterPlugin> streams = new ArrayList<>();
        List<ReducerPlugin> reducers = new ArrayList<>();

        ServiceLoader.load(EventSourcePlugin.class).forEach(sources::add);
        ServiceLoader.load(StreamAdapterPlugin.class).forEach(streams::add);
        ServiceLoader.load(ReducerPlugin.class).forEach(reducers::add);

        log.info("Discovered from classpath: {} event sources, {} stream adapters, {} reducers",
                sources.size(), streams.size(), reducers.size());

        return new DiscoveryResult(sources, streams, reducers);
    }

    /**
     * Discover plugins from external JAR directory.
     */
    public DiscoveryResult discoverFromDirectory(String directoryPath) {
        File dir = new File(directoryPath);
        if (!dir.exists()) {
            log.warn("Plugin directory does not exist: {}", directoryPath);
            return DiscoveryResult.empty();
        }

        if (!dir.isDirectory()) {
            log.warn("Plugin path is not a directory: {}", directoryPath);
            return DiscoveryResult.empty();
        }

        File[] jarFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            log.info("No JAR files found in plugin directory: {}", directoryPath);
            return DiscoveryResult.empty();
        }

        URL[] urls = Arrays.stream(jarFiles)
                .map(f -> {
                    try {
                        return f.toURI().toURL();
                    } catch (Exception e) {
                        log.warn("Failed to convert JAR to URL: {}", f.getAbsolutePath(), e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toArray(URL[]::new);

        if (urls.length == 0) {
            log.warn("No valid JARs found in plugin directory: {}", directoryPath);
            return DiscoveryResult.empty();
        }

        List<EventSourcePlugin> sources = new ArrayList<>();
        List<StreamAdapterPlugin> streams = new ArrayList<>();
        List<ReducerPlugin> reducers = new ArrayList<>();

        try (URLClassLoader loader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader())) {
            ServiceLoader.load(EventSourcePlugin.class, loader).forEach(sources::add);
            ServiceLoader.load(StreamAdapterPlugin.class, loader).forEach(streams::add);
            ServiceLoader.load(ReducerPlugin.class, loader).forEach(reducers::add);

            log.info("Discovered from {}: {} event sources, {} stream adapters, {} reducers",
                    directoryPath, sources.size(), streams.size(), reducers.size());

        } catch (Exception e) {
            log.error("Failed to load plugins from directory: {}", directoryPath, e);
            return DiscoveryResult.empty();
        }

        return new DiscoveryResult(sources, streams, reducers);
    }

    public record DiscoveryResult(
            List<EventSourcePlugin> eventSources,
            List<StreamAdapterPlugin> streamAdapters,
            List<ReducerPlugin> reducers
    ) {
        public static DiscoveryResult empty() {
            return new DiscoveryResult(List.of(), List.of(), List.of());
        }

        public DiscoveryResult merge(DiscoveryResult other) {
            List<EventSourcePlugin> mergedSources = new ArrayList<>(eventSources);
            mergedSources.addAll(other.eventSources);

            List<StreamAdapterPlugin> mergedStreams = new ArrayList<>(streamAdapters);
            mergedStreams.addAll(other.streamAdapters);

            List<ReducerPlugin> mergedReducers = new ArrayList<>(reducers);
            mergedReducers.addAll(other.reducers);

            return new DiscoveryResult(mergedSources, mergedStreams, mergedReducers);
        }
    }
}
