package com.cypherz01.kyb.check;

import com.cypherz01.kyb.companieshouse.model.CompanyProfile;
import com.cypherz01.kyb.companieshouse.model.Officer;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.matching.NameNormaliser;
import java.util.List;

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

    public SubjectExtractor(ChecksProperties properties, NameNormaliser normaliser) {}

    public SubjectExtraction extract(CompanyProfile profile, List<Officer> officers, List<Psc> pscs) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
