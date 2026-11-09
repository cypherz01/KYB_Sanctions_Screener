package com.cypherz01.kyb.check;

import java.util.List;

/**
 * Told about every completed check, inside the transaction that stores it. What it writes therefore
 * exists exactly when the check does, and {@code check} does not need to know who is listening.
 */
@FunctionalInterface
public interface CheckCompletedListener {

    /** @param subjects everyone the check screened */
    void onCheckCompleted(CheckResponse response, List<Subject> subjects, CheckOrigin origin);
}
