---
status: accepted
date: 2026-11-09
---

# Effective ownership and control

Finding a sanctioned person in a chain does not settle a decision: a 6% indirect stake and an 80% one are different facts. Sanctions rules turn on this, through OFAC's 50% rule and the UK's "owned or controlled" test. So each upstream owner's effective interest is calculated, and the decision depends on it. Companies House publishes only bands, so the result is always a range with a lower and an upper bound, never a single figure.

## The calculation

A pure function, `EffectiveOwnership`:

- On each link, take the higher of the shares and voting-rights bands: 25–50, 50–75 or 75–100.
- Multiply the bands along each path, then add the paths together, capped at 100%.
- The right to appoint or remove a majority of directors counts as **control**. It passes up the chain as 100% and is reported as control, not ownership.
- An owner **holds control** only if every link on some path is a control link. Otherwise that 100% just goes into the arithmetic: control over a company that holds 25–50% gives 25–50%.
- **Significant influence or control** on its own has bounds of 0–100%. Its upper bound can reach `REVIEW`, and its lower bound can never cause a `FAIL`.
- A nature this code does not recognise is treated as influence and noted. Not understanding a holding must not read as the holding being nothing.

Worked examples, for a sanctioned person S:

| Chain | Effective interest |
|---|---|
| S holds 75–100% of H, H holds 75–100% of the company | 56.25–100% |
| S holds 75–100% of H, H holds 50–75% | 37.5–75% |
| S holds 25–50% through each of two separate paths | 50–100% |
| S holds 25–50% of H, H holds 25–50% | 6.25–25% |

## The decision rule

This applies only above the first level.

| Match | Effective position | Result |
|---|---|---|
| STRONG | Lower bound of 50% or more, or holds control | Automatic `FAIL` |
| STRONG | Lower bound below 50% | `REVIEW`, worth 40 points, with the calculation in the evidence |
| POSSIBLE | Anywhere | The same points as a direct POSSIBLE match, with the bounds in the evidence |

A STRONG match on the company itself, an officer or a direct PSC stays an automatic `FAIL`, as in v1, whatever that party holds. If someone appears both directly and upstream, the direct rule wins. A director has no ownership band to apply, and the rule exists to reach further than v1 did, not to excuse anyone v1 would have caught.

## Considered Options

- **A single estimate, such as each band's midpoint.** Rejected. It presents a guess as a fact, and a midpoint can fall on the wrong side of 50% from the truth. Bounds state what is known, and a person can be sent the case where the bounds straddle the line.
- **Using only the lower bound, so `FAIL` at 50% and nothing below.** Rejected. A band of 25–50% through one link might be exactly 50%, and an unverifiable stake must not read as a clean result.
- **Using the upper bound for `FAIL`.** Rejected. Almost any chain containing a 75–100% link would fail automatically on a stake it may well not reach.
- **Scoring STRONG upstream matches below 50% as nothing, since the 50% rule does not apply.** Rejected. Being below a legal threshold is not the same as being safe, and a firm would want to know. It goes to a person instead.
- **Reducing POSSIBLE points with depth.** Rejected. Distance up a chain says nothing about whether a name match is correct, and it would reward longer chains.
- **Treating any control link as control of the whole chain.** Rejected. Controlling a company that holds a minority stake does not give control of the company being checked.

## Consequences

- Every band is a multiple of a quarter, so the arithmetic is exact in binary floating point, and replay compares equal without rounding.
- A `REVIEW` from this rule is worth 40 points rather than being a separate outcome, so it adds to other risks like any factor and can reach `FAIL` that way.
- Bands are as declared to Companies House. This calculates what the register says, not what is true, and a false declaration is outside what this service can detect.
