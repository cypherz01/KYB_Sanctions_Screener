package com.cypherz01.kyb.check;

import java.util.List;

/**
 * @param subjects parties to screen: the company first, then officers, then PSCs
 * @param notes parties that could not be screened and why (e.g. super-secure PSCs)
 */
public record SubjectExtraction(List<Subject> subjects, List<String> notes) {}
