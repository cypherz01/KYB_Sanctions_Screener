package com.cypherz01.kyb.ownership;

import java.util.List;

/**
 * What one owner holds over one company, as far as Companies House says. Bands are published, not
 * exact figures, so a holding is a range in percent.
 *
 * @param kind what the range describes: ownership, control or mere influence
 * @param recognised false if none of the published natures of control was understood, in which case
 *     the holding is treated as influence rather than ignored
 */
public record Holding(double lowerPercent, double upperPercent, Kind kind, boolean recognised) {

    public enum Kind {
        /** A shares, voting-rights or surplus-assets band. */
        OWNERSHIP,
        /** The right to appoint or remove a majority of directors: passes up the chain as 100%. */
        CONTROL,
        /** Significant influence or control: 0 to 100%, so it can reach REVIEW but never FAIL. */
        INFLUENCE
    }

    /**
     * Reads the Companies House natures of control. Control outranks ownership, ownership outranks
     * influence, and among ownership natures the highest band wins.
     */
    public static Holding fromNatures(List<String> naturesOfControl) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
