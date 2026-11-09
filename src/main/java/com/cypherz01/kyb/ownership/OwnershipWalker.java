package com.cypherz01.kyb.ownership;

import com.cypherz01.kyb.companieshouse.CompanyNumber;
import com.cypherz01.kyb.companieshouse.InvalidCompanyNumberException;
import com.cypherz01.kyb.companieshouse.model.Psc;
import com.cypherz01.kyb.companieshouse.model.PscKind;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.SubjectType;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Follows a company's corporate owners up the chain (ADR-0002), breadth first so the nearest owners
 * are reached before a limit is. It reads only through {@link Lookup}, so a live check and a replay
 * walk the same code over live and stored answers, and reach the same result.
 */
public class OwnershipWalker {

    /** Answers for one parent company; null if there is no answer at all. */
    @FunctionalInterface
    public interface Lookup {
        ParentRecord find(String companyNumber);
    }

    private final OwnershipProperties props;
    private final NameNormaliser normaliser;
    private final Set<String> ukCountries;

    public OwnershipWalker(OwnershipProperties props, NameNormaliser normaliser, List<String> ukCountries) {
        this.props = props;
        this.normaliser = normaliser;
        this.ukCountries =
                ukCountries.stream().map(c -> c.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    /** @param pscs the company's own PSCs, which the check already has */
    public OwnershipGraph walk(String companyNumber, String companyName, List<Psc> pscs, Lookup lookup) {
        Walk walk = new Walk(companyNumber, companyName);
        walk.expand(walk.targetId, 1, pscs);
        while (!walk.queue.isEmpty()) {
            Pending next = walk.queue.poll();
            walk.visit(next, lookup.find(next.number));
        }
        return walk.finish();
    }

    private record Pending(String id, String number, int level) {}

    private record RawLink(OwnershipLink link, List<String> natures) {}

    private static final class Node {
        final String id;
        final String name;
        final String type;
        final String companyNumber;
        final int level;
        final boolean screenable;
        final YearMonth birthDate;
        final String nationality;
        boolean followed;
        ChainStop stop;

        Node(
                String id,
                String name,
                String type,
                String companyNumber,
                int level,
                boolean screenable,
                YearMonth birthDate,
                String nationality) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.companyNumber = companyNumber;
            this.level = level;
            this.screenable = screenable;
            this.birthDate = birthDate;
            this.nationality = nationality;
        }
    }

    /** One walk's working state. */
    private final class Walk {
        final String targetId;
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final Map<String, RawLink> links = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        final Deque<Pending> queue = new ArrayDeque<>();
        int companiesQueued;
        int withheldCount;

        Walk(String companyNumber, String companyName) {
            targetId = companyId(companyNumber);
            nodes.put(targetId, new Node(targetId, companyName, "COMPANY", companyNumber, 0, false, null, null));
        }

        void visit(Pending parent, ParentRecord record) {
            Node node = nodes.get(parent.id);
            if (record == null) {
                stop(node, StopReason.UNAVAILABLE, "'%s' has no stored answer for this check".formatted(node.name));
                return;
            }
            switch (record.status()) {
                case NOT_FOUND ->
                    stop(
                            node,
                            StopReason.UNRESOLVED,
                            "'%s' gives number %s, which Companies House does not know"
                                    .formatted(node.name, parent.number));
                case UNAVAILABLE ->
                    stop(
                            node,
                            StopReason.UNAVAILABLE,
                            "'%s' could not be looked up: %s".formatted(node.name, record.reason()));
                case FOUND -> found(parent, node, record);
            }
        }

        void found(Pending parent, Node node, ParentRecord record) {
            List<Psc> current =
                    record.pscs().value().stream().filter(Psc::isCurrent).toList();
            if (!current.isEmpty()) {
                expand(parent.id, parent.level + 1, current);
            } else if (record.exemptions().value().exempt()) {
                String exemptions =
                        String.join(", ", record.exemptions().value().current());
                stop(
                        node,
                        StopReason.EXEMPT,
                        "'%s' is exempt from keeping a PSC register (%s)".formatted(node.name, exemptions));
            } else {
                stop(node, StopReason.NO_PSCS, "'%s' declares no PSCs and has no exemption".formatted(node.name));
            }
        }

        /** Adds what {@code ownedId} is held by. {@code level} is how far above the company they sit. */
        void expand(String ownedId, int level, List<Psc> pscs) {
            for (Psc psc : pscs) {
                if (!psc.isCurrent()) {
                    continue;
                }
                switch (psc.kind()) {
                    case INDIVIDUAL -> individual(ownedId, level, psc);
                    case CORPORATE, LEGAL_PERSON -> corporate(ownedId, level, psc);
                    case SUPER_SECURE, UNKNOWN -> withheld(ownedId, level, psc);
                }
            }
        }

        void individual(String ownedId, int level, Psc psc) {
            if (blank(psc.name())) {
                withheld(ownedId, level, psc);
                return;
            }
            String id = "PERSON|"
                    + normaliser.normalise(psc.name(), SubjectType.PERSON).sortedKey() + "|" + psc.dateOfBirth();
            nodes.computeIfAbsent(
                    id,
                    k -> new Node(k, psc.name(), "PERSON", null, level, true, psc.dateOfBirth(), psc.nationality()));
            link(id, ownedId, psc);
        }

        /** At the first level v1 already notes these; above it, this is where the chain goes dark. */
        void withheld(String ownedId, int level, Psc psc) {
            if (level == 1) {
                return;
            }
            boolean secure = psc.kind() == PscKind.SUPER_SECURE;
            String id = "WITHHELD|" + (++withheldCount);
            String name = secure ? "A super-secure PSC" : blank(psc.name()) ? "A PSC of unknown kind" : psc.name();
            Node node = new Node(id, name, "PERSON", null, level, false, null, null);
            nodes.put(id, node);
            link(id, ownedId, psc);
            String owned = nodes.get(ownedId).name;
            if (secure) {
                stop(
                        node,
                        StopReason.SUPER_SECURE,
                        "'%s' has a super-secure PSC, whose details are withheld".formatted(owned));
            } else {
                stop(node, StopReason.UNRESOLVED, "'%s' has a PSC of unknown kind".formatted(owned));
            }
        }

        void corporate(String ownedId, int level, Psc psc) {
            String name = blank(psc.name()) ? "An unnamed corporate PSC" : psc.name();
            String country = psc.registrationCountry();
            if (blank(country)) {
                opaque(
                        ownedId,
                        level,
                        psc,
                        name,
                        StopReason.UNRESOLVED,
                        "'%s' publishes no country of registration".formatted(name));
            } else if (!ukCountries.contains(country.trim().toLowerCase(Locale.ROOT))) {
                opaque(
                        ownedId,
                        level,
                        psc,
                        name,
                        StopReason.FOREIGN,
                        "'%s' is registered in %s, beyond what Companies House shows".formatted(name, country.trim()));
            } else {
                try {
                    company(
                            ownedId,
                            level,
                            psc,
                            name,
                            CompanyNumber.parse(psc.registrationNumber()).value());
                } catch (InvalidCompanyNumberException e) {
                    opaque(
                            ownedId,
                            level,
                            psc,
                            name,
                            StopReason.UNRESOLVED,
                            "'%s' has no usable company number".formatted(name));
                }
            }
        }

        void company(String ownedId, int level, Psc psc, String name, String number) {
            String id = companyId(number);
            Node existing = nodes.get(id);
            if (existing != null) {
                boolean loop = sitsAbove(ownedId, id);
                link(id, ownedId, psc);
                if (loop) {
                    stop(
                            existing,
                            StopReason.CIRCULAR,
                            "'%s' is owned, through the chain, by itself".formatted(existing.name));
                }
                return;
            }
            Node node = new Node(id, name, "COMPANY", number, level, true, null, null);
            nodes.put(id, node);
            link(id, ownedId, psc);
            if (level > props.maxDepth()) {
                stop(
                        node,
                        StopReason.DEPTH_LIMIT,
                        "'%s' is beyond the limit of %d levels".formatted(name, props.maxDepth()));
            } else if (companiesQueued >= props.maxCompanies()) {
                stop(
                        node,
                        StopReason.COMPANY_LIMIT,
                        "'%s' is beyond the limit of %d companies per check".formatted(name, props.maxCompanies()));
            } else {
                companiesQueued++;
                node.followed = true;
                queue.add(new Pending(id, number, level));
            }
        }

        void opaque(String ownedId, int level, Psc psc, String name, StopReason reason, String detail) {
            String id = "ORGANISATION|"
                    + normaliser.normalise(name, SubjectType.ORGANISATION).sortedKey();
            Node node = nodes.computeIfAbsent(
                    id, k -> new Node(k, name, "ORGANISATION", null, level, !blank(psc.name()), null, null));
            link(id, ownedId, psc);
            stop(node, reason, detail);
        }

        /** True if {@code upper} is {@code start} or already owns it, directly or through others. */
        boolean sitsAbove(String upper, String start) {
            if (upper.equals(start)) {
                return true;
            }
            Deque<String> pending = new ArrayDeque<>(List.of(start));
            Set<String> seen = new HashSet<>(pending);
            while (!pending.isEmpty()) {
                String current = pending.poll();
                for (RawLink raw : links.values()) {
                    String owner = raw.link().owner();
                    if (raw.link().owned().equals(current) && seen.add(owner)) {
                        if (owner.equals(upper)) {
                            return true;
                        }
                        pending.add(owner);
                    }
                }
            }
            return false;
        }

        /** The same owner listed twice over one company keeps the higher holding, not the sum. */
        void link(String ownerId, String ownedId, Psc psc) {
            Holding holding = Holding.fromNatures(psc.naturesOfControl());
            if (!holding.recognised() && !psc.naturesOfControl().isEmpty()) {
                notes.add("The control held by '%s' over '%s' is not understood (%s), so it is treated as influence"
                        .formatted(
                                nodes.get(ownerId).name,
                                nodes.get(ownedId).name,
                                String.join(", ", psc.naturesOfControl())));
            }
            String key = ownerId + "->" + ownedId;
            RawLink existing = links.get(key);
            if (existing == null
                    || holding.lowerPercent() > existing.link().holding().lowerPercent()) {
                links.put(key, new RawLink(new OwnershipLink(ownerId, ownedId, holding), psc.naturesOfControl()));
            }
        }

        void stop(Node node, StopReason reason, String detail) {
            if (node.stop == null) {
                node.stop = new ChainStop(reason, node.name, detail);
            }
        }

        OwnershipGraph finish() {
            List<OwnershipLink> plain =
                    links.values().stream().map(RawLink::link).toList();
            Map<String, EffectiveInterest> interests = EffectiveOwnership.calculate(targetId, plain);

            List<OwnershipTree.Node> treeNodes = new ArrayList<>();
            List<OwnershipGraph.Party> parties = new ArrayList<>();
            List<ChainStop> stops = new ArrayList<>();
            for (Node n : nodes.values()) {
                EffectiveInterest interest = interests.get(n.id);
                treeNodes.add(new OwnershipTree.Node(n.id, n.name, n.type, n.companyNumber, interest, n.stop));
                if (n.stop != null) {
                    stops.add(n.stop);
                }
                if (n.level >= 2 && n.screenable && interest != null) {
                    parties.add(new OwnershipGraph.Party(
                            n.name, n.followed, n.type.equals("PERSON"), n.birthDate, n.nationality, interest));
                }
            }
            List<OwnershipTree.Link> treeLinks = links.values().stream()
                    .map(raw -> new OwnershipTree.Link(
                            raw.link().owner(),
                            raw.link().owned(),
                            raw.natures(),
                            raw.link().holding().lowerPercent(),
                            raw.link().holding().upperPercent(),
                            raw.link().holding().kind()))
                    .toList();
            return new OwnershipGraph(
                    new OwnershipTree(true, List.copyOf(treeNodes), treeLinks),
                    List.copyOf(parties),
                    List.copyOf(stops),
                    List.copyOf(notes));
        }
    }

    private static String companyId(String number) {
        return "COMPANY|" + number;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
