
package com.wildforge.content;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, namespaced identifier such as "wildforge:stone".
 */
public final class ResourceId implements Comparable<ResourceId> {

    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z0-9_.-]+");

    private static final Pattern PATH =
            Pattern.compile("[a-z0-9/._-]+");

    private final String namespace;
    private final String path;

    public ResourceId(String namespace, String path) {
        if (namespace == null
                || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Invalid namespace: " + namespace);
        }

        if (path == null
                || !PATH.matcher(path).matches()
                || path.startsWith("/")
                || path.endsWith("/")
                || path.contains("//")) {
            throw new IllegalArgumentException(
                    "Invalid resource path: " + path);
        }

        this.namespace = namespace;
        this.path = path;
    }

    public static ResourceId parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Resource ID cannot be blank");
        }

        int separator = value.indexOf(':');

        // Unqualified IDs default to the Wildforge namespace.
        if (separator < 0) {
            return new ResourceId("wildforge", value);
        }

        if (separator == 0
                || separator != value.lastIndexOf(':')
                || separator == value.length() - 1) {
            throw new IllegalArgumentException(
                    "Invalid resource ID: " + value);
        }

        return new ResourceId(
                value.substring(0, separator),
                value.substring(separator + 1));
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof ResourceId)) {
            return false;
        }

        ResourceId that = (ResourceId) other;

        return namespace.equals(that.namespace)
                && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }

    @Override
    public int compareTo(ResourceId other) {
        return toString().compareTo(other.toString());
    }
}
