package org.jsoup.compare;

import org.jsoup.helper.Validate;
import org.jspecify.annotations.Nullable;

/**
 Describes the first difference found between two canonical streams.
 <p>
 A difference is either a token mismatch ({@link #expected()} and {@link #actual()} both non-null) or a missing
 token at the end of one stream (exactly one is null, indicating the other subtree ends earlier or has extra
 content).
 </p>
 * @since 1.24.1
 */
public final class Difference {
    private final NodePath path;
    private final @Nullable String expected;
    private final @Nullable String actual;
    private final String message;

    Difference(NodePath path, @Nullable String expected, @Nullable String actual) {
        Validate.notNull(path);
        this.path = path;
        this.expected = expected;
        this.actual = actual;
        if (expected == null) {
            message = "expected subtree ends at " + path + " but actual continues with: " + actual;
        } else if (actual == null) {
            message = "actual subtree ends at " + path + " but expected continues with: " + expected;
        } else {
            message = "expected " + expected + " but found " + actual + " at " + path;
        }
    }

    /**
     Returns the path of the differing node.
     @return the node path
     */
    public NodePath path() {
        return path;
    }

    /**
     Returns a human-readable description of the expected token, or {@code null} if the expected subtree ends here.
     @return the expected token description, or null
     */
    public @Nullable String expected() {
        return expected;
    }

    /**
     Returns a human-readable description of the actual token, or {@code null} if the actual subtree ends here.
     @return the actual token description, or null
     */
    public @Nullable String actual() {
        return actual;
    }

    /**
     Returns a human-readable message describing the difference.
     @return the message
     */
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return message;
    }
}
