package com.cypherz01.kyb.check;

import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.PartialDate;
import com.cypherz01.kyb.matching.SubjectType;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides who is screened (spec §9): the company itself, every current officer (resigned too if
 * configured) and every current PSC, individual or corporate.
 *
 * <ul>
 *   <li>Individual officers' {@code SURNAME, Forenames} names are put in natural order; corporate
 *       officers and corporate or legal-person PSCs are screened as organisations.
 *   <li>Month and year of birth are kept; demonym nationalities are mapped to ISO codes, and
 *       unknown ones are left out rather than guessed.
 *   <li>Super-secure PSCs (details withheld) cannot be screened and are noted.
 *   <li>The same party in several roles (same normalised name, type and birth month) is one
 *       subject holding all those roles.
 * </ul>
 */
public class SubjectExtractor {

    private final ChecksProperties props;
    private final NameNormaliser normaliser;
    private final Map<String, String> nationalities = new LinkedHashMap<>();

    public SubjectExtractor(ChecksProperties properties, NameNormaliser normaliser) {
        this.props = properties;
        this.normaliser = normaliser;
        properties.nationalities().forEach((k, v) -> nationalities.put(k.trim().toLowerCase(Locale.ROOT), v));
    }

    public SubjectExtraction extract(CompanyProfile profile, List<Officer> officers, List<Psc> pscs) {
        Map<String, Builder> subjects = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        add(subjects, SubjectRole.COMPANY, profile.name(), profile.name(), SubjectType.ORGANISATION, null, Set.of());

        for (Officer o : officers) {
            if (!o.isCurrent() && !props.includeResignedOfficers()) {
                continue;
            }
            if (o.name() == null || o.name().isBlank()) {
                continue;
            }
            if (o.corporate()) {
                add(
                        subjects,
                        SubjectRole.CORPORATE_OFFICER,
                        o.name(),
                        o.name(),
                        SubjectType.ORGANISATION,
                        null,
                        Set.of());
            } else {
                add(
                        subjects,
                        officerRole(o.role()),
                        o.name(),
                        NameNormaliser.surnameFirstToNatural(o.name()),
                        SubjectType.PERSON,
                        birthDate(o.dateOfBirth()),
                        countries(o.nationality()));
            }
        }

        for (Psc p : pscs) {
            if (!p.isCurrent()) {
                continue;
            }
            switch (p.kind()) {
                case INDIVIDUAL ->
                    add(
                            subjects,
                            SubjectRole.PSC,
                            p.name(),
                            p.name(),
                            SubjectType.PERSON,
                            birthDate(p.dateOfBirth()),
                            countries(p.nationality()));
                case CORPORATE, LEGAL_PERSON ->
                    add(
                            subjects,
                            SubjectRole.CORPORATE_PSC,
                            p.name(),
                            p.name(),
                            SubjectType.ORGANISATION,
                            null,
                            Set.of());
                case SUPER_SECURE ->
                    notes.add("A super-secure PSC (details withheld by Companies House) cannot be screened");
                case UNKNOWN ->
                    notes.add("A PSC of unknown kind" + (p.name() == null ? "" : " ('" + p.name() + "')")
                            + " was not screened");
            }
        }

        List<Subject> result = subjects.values().stream().map(Builder::build).toList();
        return new SubjectExtraction(result, List.copyOf(notes));
    }

    private void add(
            Map<String, Builder> subjects,
            SubjectRole role,
            String published,
            String screened,
            SubjectType type,
            PartialDate birthDate,
            Set<String> countries) {
        if (screened == null || screened.isBlank()) {
            return;
        }
        String key = type + "|" + normaliser.normalise(screened, type).sortedKey() + "|" + birthDate;
        Builder existing = subjects.get(key);
        if (existing != null) {
            existing.roles.add(role);
            existing.countries.addAll(countries);
            return;
        }
        subjects.put(key, new Builder(role, published, screened, type, birthDate, countries));
    }

    private static SubjectRole officerRole(String role) {
        String r = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if (r.contains("director")) {
            return SubjectRole.DIRECTOR;
        }
        if (r.contains("secretary")) {
            return SubjectRole.SECRETARY;
        }
        return SubjectRole.OFFICER;
    }

    private static PartialDate birthDate(YearMonth dob) {
        return dob == null ? null : new PartialDate(dob.getYear(), dob.getMonthValue(), null);
    }

    /** "British,Irish" and similar lists are split; unknown demonyms are dropped. */
    private Set<String> countries(String nationality) {
        if (nationality == null || nationality.isBlank()) {
            return Set.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String part : nationality.split("[,/;]")) {
            String code = nationalities.get(part.trim().toLowerCase(Locale.ROOT));
            if (code != null) {
                codes.add(code.toLowerCase(Locale.ROOT));
            }
        }
        return codes;
    }

    private static final class Builder {
        final LinkedHashSet<SubjectRole> roles = new LinkedHashSet<>();
        final String published;
        final String screened;
        final SubjectType type;
        final PartialDate birthDate;
        final LinkedHashSet<String> countries;

        Builder(
                SubjectRole role,
                String published,
                String screened,
                SubjectType type,
                PartialDate birthDate,
                Set<String> countries) {
            roles.add(role);
            this.published = published;
            this.screened = screened;
            this.type = type;
            this.birthDate = birthDate;
            this.countries = new LinkedHashSet<>(countries);
        }

        Subject build() {
            return new Subject(List.copyOf(roles), published, screened, type, birthDate, Set.copyOf(countries));
        }
    }
}
