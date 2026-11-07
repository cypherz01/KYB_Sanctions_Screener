package com.cypherz01.kyb.matching;

/**
 * The four name-similarity components (spec §8.3), each 0-1. They are kept so every score can be
 * explained and the weights tuned.
 *
 * @param jaroWinkler Jaro-Winkler similarity of the token-sorted names
 * @param tokenOverlap share of the shorter name's tokens found exactly in the other
 * @param phonetic share of the shorter name's tokens with a phonetic match in the other
 * @param alignment mean best one-to-one token similarity (absorbs single-token typos)
 */
public record ComponentScores(double jaroWinkler, double tokenOverlap, double phonetic, double alignment) {}
