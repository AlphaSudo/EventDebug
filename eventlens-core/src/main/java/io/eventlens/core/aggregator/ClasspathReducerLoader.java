package io.eventlens.core.aggregator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * Dynamically loads user-provided {@link AggregateReducer} implementations
 * from external JARs at runtime.
 *
 * <p>
 * <b>Two loading strategies:</b>
 * <ol>
 * <li><b>ServiceLoader</b> — JAR contains
 * {@code META-INF/services/io.eventlens.core.aggregator.AggregateReducer}.
 * Each listed class is instantiated and registered by its reported
 * {@link AggregateReducer#aggregateType()}.</li>
 * <li><b>Named class</b> — explicit
 * {@code "AggregateType" → "com.example.MyReducer"}
 * mapping in {@code eventlens.yaml}. The class is loaded from the provided
 * JARs.</li>
 * </ol>
 *
 * <p>
 * <b>CLI usage:</b>
 * 
 * <pre>{@code
 *   eventlens serve \
 *     --db-url jdbc:postgresql://... \
 *     --classpath /path/to/my-domain.jar
 * }</pre>
 */
public class ClasspathReducerLoader {

    private static final Logger log = LoggerFactory.getLogger(ClasspathReducerLoader.class);

    /**
     * Load all reducers discoverable via ServiceLoader from the given JAR paths
     * and register them into the provided registry.
     *
     * @param registry the registry to populate
     * @param jarPaths one or more JAR file paths
     */
    public void loadFromJars(ReducerRegistry registry, List<String> jarPaths) {
        if (jarPaths == null || jarPaths.isEmpty())
            return;

        URL[] urls = toUrls(jarPaths);
        try (URLClassLoader loader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader())) {
            ServiceLoader<AggregateReducer> services = ServiceLoader.load(AggregateReducer.class, loader);

            int count = 0;
            for (AggregateReducer reducer : services) {
                String type = reducer.aggregateType();
                if (type != null && !type.isBlank()) {
                    registry.register(type, reducer);
                    count++;
                } else {
                    log.warn("Reducer '{}' returned null/blank aggregateType() — skipping auto-registration",
                            reducer.getClass().getName());
                }
            }
            log.info("Loaded {} reducer(s) from {} JAR(s) via ServiceLoader", count, jarPaths.size());

        } catch (Exception e) {
            throw new RuntimeException("Failed to load reducers from classpath JARs: " + jarPaths, e);
        }
    }

    /**
     * Load a specific reducer class by name from the given JARs and register it.
     *
     * @param registry      the registry to populate
     * @param jarPaths      JARs containing the class
     * @param aggregateType the aggregate type to register for
     * @param className     fully-qualified class name (e.g.
     *                      "com.myapp.BankAccountReducer")
     */
    public void loadNamedReducer(ReducerRegistry registry,
            List<String> jarPaths,
            String aggregateType,
            String className) {
        URL[] urls = toUrls(jarPaths);
        try (URLClassLoader loader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader())) {
            Class<?> cls = loader.loadClass(className);
            if (!AggregateReducer.class.isAssignableFrom(cls)) {
                throw new IllegalArgumentException(
                        className + " does not implement AggregateReducer");
            }
            AggregateReducer reducer = (AggregateReducer) cls.getDeclaredConstructor().newInstance();
            registry.register(aggregateType, reducer);
            log.info("Loaded named reducer '{}' for aggregate type '{}'", className, aggregateType);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load reducer '" + className + "' for type '" + aggregateType + "'", e);
        }
    }

    /**
     * Convenience method: load all reducers from JARs, then load named reducers
     * from config.
     */
    public void loadAll(ReducerRegistry registry, List<String> jarPaths, Map<String, String> namedReducers) {
        if (jarPaths != null && !jarPaths.isEmpty()) {
            loadFromJars(registry, jarPaths);
        }
        if (namedReducers != null) {
            namedReducers.forEach(
                    (aggregateType, className) -> loadNamedReducer(registry, jarPaths != null ? jarPaths : List.of(),
                            aggregateType, className));
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private URL[] toUrls(List<String> jarPaths) {
        return jarPaths.stream().map(path -> {
            try {
                File f = new File(path);
                if (!f.exists()) {
                    throw new IllegalArgumentException("JAR not found: " + f.getAbsolutePath());
                }
                return f.toURI().toURL();
            } catch (Exception e) {
                throw new RuntimeException("Invalid JAR path: " + path, e);
            }
        }).toArray(URL[]::new);
    }
}
