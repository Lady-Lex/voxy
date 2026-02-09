package me.cortex.voxy.client.core.gl.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShaderLoader {
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*#import\\s+<([^>]+)>\\s*$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^\\s*#version\\s+.*\\n");

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    public static String parse(String id) {
        return CACHE.computeIfAbsent(id, ShaderLoader::parseUncached);
    }

    private static String parseUncached(String id) {
        // Voxy shaders are authored with Sodium-style `#import <namespace:path>`.
        // Under NeoForge + Connector, Sodium's own ShaderLoader may not be able to see resources from other mods.
        // We therefore expand imports ourselves using this mod's class loader.
        StringBuilder out = new StringBuilder(16 * 1024);
        out.append("#version 460 core\n");
        out.append(resolveAndExpand(id, new ArrayDeque<>()));
        return normalizeNewlines(out.toString());
    }

    private static String resolveAndExpand(String id, Deque<String> importStack) {
        if (importStack.contains(id)) {
            throw new RuntimeException("Cyclic shader import detected: " + importStack + " -> " + id);
        }

        importStack.push(id);

        String source = readShaderSource(id);
        source = normalizeNewlines(source);

        // Ensure a single #version line (we inject one at the top of the final output).
        source = VERSION_PATTERN.matcher(source).replaceFirst("");

        StringBuilder out = new StringBuilder(source.length() + 256);
        String[] lines = source.split("\\n", -1);
        for (String line : lines) {
            Matcher matcher = IMPORT_PATTERN.matcher(line);
            if (matcher.matches()) {
                String importedId = matcher.group(1).trim();
                out.append(resolveAndExpand(importedId, importStack));
            } else {
                out.append(line).append('\n');
            }
        }

        importStack.pop();
        return out.toString();
    }

    private static String readShaderSource(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            throw new IllegalArgumentException("Invalid shader id: " + id);
        }

        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        String resourcePath = "assets/" + namespace + "/shaders/" + path;

        ClassLoader classLoader = ShaderLoader.class.getClassLoader();
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: /" + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader: /" + resourcePath, e);
        }
    }

    private static String normalizeNewlines(String text) {
        // Keep GLSL line mapping stable across platforms.
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }
}
