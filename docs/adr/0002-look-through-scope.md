---
status: accepted
date: 2026-11-09
---

# Look-through scope

v1 stops at a company's declared PSCs (persons with significant control), so an owner behind a holding company is never screened. A check now follows corporate PSCs up the chain, but only those registered in the UK with a number Companies House recognises. It follows at most 5 levels and fetches at most 25 companies. At each upper level it screens the company and its PSCs, not its officers. The aim is to find who owns a company using only free, authoritative data, and to be explicit about where that data runs out.

## Where a chain stops

Stopping is never read as clean. Each way a chain can end is recorded on the check, and only two endings raise no factor: reaching a person, and reaching a parent that is lawfully exempt from declaring PSCs.

| Ending | Result | Why |
|---|---|---|
| A person | No factor | The natural end of a chain |
| Parent exempt from the PSC regime, such as a listed company | Note | Listed groups declare no PSCs by law. Scoring this would penalise them for following the rules |
| Foreign owner | `OPAQUE_OWNERSHIP`, 15 | Companies House cannot see past it |
| No usable company number, or one Companies House does not know | `OPAQUE_OWNERSHIP`, 15 | |
| Parent declaring no PSCs and no exemption | `OPAQUE_OWNERSHIP`, 15 | |
| Super-secure PSC above the first level | `OPAQUE_OWNERSHIP`, 15 | |
| Parent lookup fails during an outage | `OPAQUE_OWNERSHIP`, 15 | The check still completes, because the company itself was found |
| Depth or company limit reached | `OWNERSHIP_LIMIT_REACHED`, 15 | |
| Chain leads back to a company already in it | `CIRCULAR_OWNERSHIP`, 25 | A loop has no real owner at the top, which is more suspicious than a gap |

The 15 points match `NO_PSC_DECLARED`, which means the same thing: the owner cannot be seen. All of these stay below the review threshold of 40, because a UK subsidiary of a foreign group is common and should show as a factor without needing a person. A direct foreign PSC raises both `CORPORATE_PSC_OFFSHORE` and `OPAQUE_OWNERSHIP`, since one is about jurisdiction and the other about visibility. A chain cut off by a limit or a gap still has every name reached so far screened.

## Checking that a number means what it says

Registration numbers are free text, and digits are padded to make a company number, so a charity's number can become the number of an unrelated company. Following it would screen the wrong people, and could fail a company over someone with no link to it. So the name Companies House holds for that number must match the name on the PSC entry, using the same matcher as sanctions at its `POSSIBLE` threshold. A mismatch is `OPAQUE_OWNERSHIP`, and that company's owners are not followed. A company renamed since its parent last updated the register will show as opaque, which is the safe way to be wrong.

Both names are screened, because the entry and the register can differ, and a sanctions list could hold either.

A lookup is not all-or-nothing. A parent's exemptions only matter if it declares nobody, and its profile only confirms the name, so losing either must not discard owners who loaded. The chain stops as unavailable only when the PSCs themselves are missing, or when a parent declares nobody and its exemptions are missing.

## Limits

Each company costs three requests: profile, PSCs and exemptions. The 25-company cap therefore means about 75 requests a check plus any pagination, well inside the client's limit of 550 per 5 minutes. A depth of 5 was chosen because each level multiplies the bands, so an owner that far up rarely reaches any threshold unless every link is a large stake, and such a long chain is itself reported.

The limits sit in `scoring-rules.yml`, not with the other settings, because changing one changes decisions. A replay after such a change is then explained by the scoring version it records.

## Considered Options

- **Trusting `place_registered` to confirm a Companies House number.** Rejected after checking real data. It is free text: "Companies House" and "Companies House Register" both appear, and HSBC Holdings gives "United Kingdom". Requiring a fixed value would have made real UK parents opaque, while the name check tests the thing that matters.
- **Screening officers at every level.** Rejected. Sanctions ownership tests look at who owns and controls, and an upstream company's officers are neither by that fact alone. It would also multiply requests and false positives, and process personal data about people with no established link to the company.
- **OpenSanctions FtM `Ownership` entities.** They would add ownership links from a second source, including outside the UK, and could reach past a foreign parent. Noted and not built: merging two ownership pictures that disagree is a design of its own, and the parser keeps skipping these entities for now.
- **Stopping at the first gap with no factor, as v1 effectively does.** Rejected. A chain that could not be followed is exactly what someone hiding ownership would arrange.
- **Sending any opaque chain to `REVIEW`.** Rejected. Every UK subsidiary of a foreign group would need a person, which would teach reviewers to ignore the signal.
- **Refusing to complete a check when a parent lookup fails.** Rejected. It would let an outage on one upstream company block a decision, and the factor already records that the chain is unverified. This follows the approach v1 took to FCA outages.

## Consequences

- A not-found or unavailable parent needs its own snapshot, because `CompaniesHouseClient` does not cache a 404 and replay makes no external calls.
- Every check made from M8 onwards stores a look-through marker snapshot, so "made before M8" can be told apart from "had no parents to fetch".
- A check made before M8 replays at depth 0: nothing is followed, no chain factor fires, and it carries the note "ownership look-through not performed at check time". The difference is reported and explained by the engine version moving to 2.0.0. Not having looked is different from having looked and found a gap, so it is not scored as one.
