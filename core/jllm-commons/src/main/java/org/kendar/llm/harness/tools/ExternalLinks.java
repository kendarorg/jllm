package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * What the file tools do with a symlink or junction inside the work dir whose target lies outside it
 * ({@code files.externalLinks}). A link whose target stays inside the work dir is always followed.
 */
public enum ExternalLinks {
    /** Never followed: reads and writes through the link are refused. The web host always uses this. */
    NONE,
    /** Followed by the read tools; writes through the link are refused. The CLI default. */
    READ,
    /** Followed by reads and writes. */
    READ_WRITE;

    /** The CLI default of {@code files.externalLinks}. */
    public static final ExternalLinks DEFAULT = READ;

    /** The settings value: {@code none}, {@code read} or {@code readwrite}. */
    @JsonValue
    public String settingValue() {
        return switch (this) {
            case NONE -> "none";
            case READ -> "read";
            case READ_WRITE -> "readwrite";
        };
    }

    /** True when reads follow a link that leaves the work dir. */
    public boolean followsOnRead() {
        return this != NONE;
    }

    /** True when writes follow a link that leaves the work dir. */
    public boolean followsOnWrite() {
        return this == READ_WRITE;
    }

    /**
     * Parses a settings value ({@code none}, {@code read}, {@code readwrite}; case-insensitive).
     *
     * @param value null or blank: {@code dflt}
     * @throws IllegalArgumentException for any other value
     */
    public static ExternalLinks parse(String value, ExternalLinks dflt) {
        if (value == null || value.isBlank()) return dflt;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "none" -> NONE;
            case "read" -> READ;
            case "readwrite" -> READ_WRITE;
            default -> throw new IllegalArgumentException(
                    "files.externalLinks must be none, read or readwrite, not: " + value);
        };
    }

    @JsonCreator
    static ExternalLinks fromJson(String value) {
        return parse(value, null);
    }
}
