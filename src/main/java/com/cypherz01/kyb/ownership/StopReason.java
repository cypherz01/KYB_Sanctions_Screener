package com.cypherz01.kyb.ownership;

/** Why an ownership chain could not be followed any further. */
public enum StopReason {
    /** The parent is exempt from the PSC regime, e.g. a listed company: a clean end. */
    EXEMPT(Category.CLEAN),
    FOREIGN(Category.OPAQUE),
    /** No usable company number, or Companies House does not know the number. */
    UNRESOLVED(Category.OPAQUE),
    /** A parent that declares no PSCs and has no exemption. */
    NO_PSCS(Category.OPAQUE),
    SUPER_SECURE(Category.OPAQUE),
    /** Companies House could not be reached for this parent. An outage is not evidence of anything. */
    UNAVAILABLE(Category.OPAQUE),
    DEPTH_LIMIT(Category.LIMIT),
    COMPANY_LIMIT(Category.LIMIT),
    CIRCULAR(Category.CIRCULAR);

    /** Which factor, if any, this kind of ending raises. */
    public enum Category {
        CLEAN,
        OPAQUE,
        LIMIT,
        CIRCULAR
    }

    private final Category category;

    StopReason(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
