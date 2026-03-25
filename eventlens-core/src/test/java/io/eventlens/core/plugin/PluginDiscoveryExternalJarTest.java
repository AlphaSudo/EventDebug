package io.eventlens.core.plugin;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDiscoveryExternalJarTest {

    @Test
    void discoversEventSourcePluginFromExternalJar() throws Exception {
        Path pluginDir = Files.createTempDirectory("eventlens-plugin-dir");
        Path classesDir = Files.createTempDirectory("eventlens-plugin-classes");
        Path sourceFile = writeSourceFile(pluginDir);

        compileDummyPlugin(sourceFile, classesDir);
        Path jarFile = createPluginJar(pluginDir, classesDir);

        PluginDiscovery.DiscoveryResult result = new PluginDiscovery().discoverFromDirectory(pluginDir.toString());

        assertThat(Files.exists(jarFile)).isTrue();
        assertThat(result.eventSources()).hasSize(1);
        assertThat(result.eventSources().getFirst().typeId()).isEqualTo("dummy-external");
        assertThat(result.streamAdapters()).isEmpty();
    }

    private static Path writeSourceFile(Path pluginDir) throws IOException {
        Path sourceDir = Files.createDirectories(pluginDir.resolve("src/testplugins"));
        Path sourceFile = sourceDir.resolve("DummyExternalEventSourcePlugin.java");
        Files.writeString(sourceFile, """
                package testplugins;

                import io.eventlens.spi.Event;
                import io.eventlens.spi.EventQuery;
                import io.eventlens.spi.EventQueryResult;
                import io.eventlens.spi.EventSourcePlugin;
                import io.eventlens.spi.HealthStatus;

                import java.time.Instant;
                import java.util.List;
                import java.util.Map;

                public final class DummyExternalEventSourcePlugin implements EventSourcePlugin {
                    @Override
                    public String typeId() {
                        return \"dummy-external\";
                    }

                    @Override
                    public String displayName() {
                        return \"Dummy External Plugin\";
                    }

                    @Override
                    public void initialize(String instanceId, Map<String, Object> config) {
                    }

                    @Override
                    public EventQueryResult query(EventQuery query) {
                        Event event = new Event(
                                \"evt-1\",
                                \"ACC-001\",
                                \"BankAccount\",
                                1,
                                \"AccountCreated\",
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                                Instant.parse(\"2026-01-01T00:00:00Z\"),
                                1
                        );
                        return new EventQueryResult(List.of(event), false, null);
                    }

                    @Override
                    public HealthStatus healthCheck() {
                        return HealthStatus.up();
                    }
                }
                """, StandardCharsets.UTF_8);
        return sourceFile;
    }

    private static void compileDummyPlugin(Path sourceFile, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesDir));
            var compilationUnits = fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));
            String classpath = System.getProperty("java.class.path");
            Boolean success = compiler.getTask(null, fileManager, null, List.of("-classpath", classpath), null, compilationUnits).call();
            assertThat(success).isTrue();
        }
    }

    private static Path createPluginJar(Path pluginDir, Path classesDir) throws IOException {
        Path jarFile = pluginDir.resolve("dummy-external-plugin.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarFile))) {
            Path classFile = classesDir.resolve(Path.of("testplugins", "DummyExternalEventSourcePlugin.class"));
            jar.putNextEntry(new JarEntry("testplugins/DummyExternalEventSourcePlugin.class"));
            jar.write(Files.readAllBytes(classFile));
            jar.closeEntry();

            jar.putNextEntry(new JarEntry("META-INF/services/io.eventlens.spi.EventSourcePlugin"));
            jar.write("testplugins.DummyExternalEventSourcePlugin\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return jarFile;
    }
}

