package com.cypherz01.kyb.ownership;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Suffixes such as -as-trust, -as-firm and -limited-liability-partnership do not change the band.
    private static final Pattern BAND = Pattern.compile(
            "(?:ownership-of-shares|voting-rights|right-to-share-surplus-assets|part-right-to-share-surplus-assets)"
                    + "-(25-to-50|50-to-75|75-to-100|more-than-25)-percent(?:-.*)?");
    private static final Pattern CONTROL =
            Pattern.compile("right-to-appoint-and-remove-(?:directors|members|person).*");
    private static final Pattern INFLUENCE = Pattern.compile("significant-influence-or-control.*");

    /**
     * Reads the Companies House natures of control. Control outranks ownership, ownership outranks
     * influence, and among ownership natures the highest band wins.
     */
    public static Holding fromNatures(List<String> naturesOfControl) {
        Holding best = null;
        boolean influence = false;
        for (String nature : naturesOfControl) {
            if (CONTROL.matcher(nature).matches()) {
                return new Holding(100, 100, Kind.CONTROL, true);
            }
            influence |= INFLUENCE.matcher(nature).matches();
            Matcher band = BAND.matcher(nature);
            if (band.matches()) {
                Holding candidate = ofBand(band.group(1));
                if (best == null || isHigher(candidate, best)) {
                    best = candidate;
                }
            }
        }
        return best != null ? best : new Holding(0, 100, Kind.INFLUENCE, influence);
    }

    private static Holding ofBand(String band) {
        return switch (band) {
            case "25-to-50" -> new Holding(25, 50, Kind.OWNERSHIP, true);
            case "50-to-75" -> new Holding(50, 75, Kind.OWNERSHIP, true);
            case "75-to-100" -> new Holding(75, 100, Kind.OWNERSHIP, true);
            default -> new Holding(25, 100, Kind.OWNERSHIP, true); // "more than 25%", on overseas entities
        };
    }

    private static boolean isHigher(Holding a, Holding b) {
        return a.lowerPercent() > b.lowerPercent()
                || (a.lowerPercent() == b.lowerPercent() && a.upperPercent() > b.upperPercent());
    }
}
