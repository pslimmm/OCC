package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdvisoryGroupService {
    private final AdvisoryProposalRepository proposals;
    private final InstructionService instructions;
    private final IssuedInstructionRepository issued;
    private final PlanningScopePort scopes;
    private final AssignmentBusGuards guards;
    private final EntityManager entityManager;
    private final Clock clock;

    public AdvisoryGroupService(AdvisoryProposalRepository proposals, InstructionService instructions,
            IssuedInstructionRepository issued, PlanningScopePort scopes, AssignmentBusGuards guards,
            EntityManager entityManager, Clock clock) {
        this.proposals = proposals; this.instructions = instructions; this.issued = issued; this.scopes = scopes;
        this.guards = guards; this.entityManager = entityManager; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AdvisoryGroup> pending(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size");
        var request = PageRequest.of(page, size);
        var runId = scopes.current().demoRunId();
        if (runId == null) return Page.empty(request);
        Map<AdvisoryGroup.Key, List<AdvisoryProposal>> groups = new LinkedHashMap<>();
        for (var proposal : instructions.currentPending(runId))
            groups.computeIfAbsent(AdvisoryGroup.Key.from(proposal), key -> new ArrayList<>()).add(proposal);
        var pageGroups = groups.entrySet().stream().skip(request.getOffset()).limit(size).toList();
        var movementBusIds = pageGroups.stream().flatMap(group -> group.getValue().stream())
                .filter(AdvisoryGroupService::isMovement)
                .map(proposal -> proposal.bus().busId()).collect(Collectors.toSet());
        var activeMovement = activeMovement(runId, movementBusIds);
        var found = pageGroups.stream().map(group -> {
            var key = group.getKey();
            var groupMembers = List.copyOf(group.getValue());
            var conflicts = groupMembers.stream().filter(AdvisoryGroupService::isMovement)
                    .filter(value -> activeMovement.containsKey(value.bus().busId())).map(value -> {
                        var previous = activeMovement.get(value.bus().busId());
                        return new AdvisoryGroup.MovementConflict(value.id(), previous.busId(), previous.id(), previous.message());
                    }).toList();
            return new AdvisoryGroup(key, groupMembers, conflicts);
        }).toList();
        return new PageImpl<>(found, request, groups.size());
    }

    @Transactional
    public List<IssuedInstruction> decide(List<UUID> proposalIds, PlanningActor actor) {
        return decide(proposalIds, Map.of(), actor);
    }

    @Transactional
    public List<IssuedInstruction> decide(List<UUID> proposalIds, Map<UUID, UUID> replacements, PlanningActor actor) {
        actor.require(PlanningActor.Role.CONTROLLER);
        if (proposalIds == null || proposalIds.isEmpty()
                || proposalIds.stream().anyMatch(id -> id == null) || new HashSet<>(proposalIds).size() != proposalIds.size())
            throw new IllegalArgumentException("Select all distinct proposals from the reviewed advisory group");
        if (!proposalIds.containsAll(replacements.keySet()) || replacements.values().stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Replacement instructions must belong to reviewed proposals");
        var keys = proposalIds.stream().map(id -> proposals.findKeyById(id)
                .map(key -> new AssignmentBusGuards.Key(key.getDemoRunId(), key.getBusId()))
                .orElseThrow(AdvisoryNotFoundException::new)).toList();
        guards.lockAll(keys);
        var selected = proposalIds.stream().sorted().map(id -> proposals.findForUpdate(id)
                .orElseThrow(AdvisoryNotFoundException::new)).toList();
        selected.forEach(entityManager::refresh);
        var key = AdvisoryGroup.Key.from(selected.getFirst());
        if (!key.demoRunId().equals(scopes.current().demoRunId())
                || selected.stream().anyMatch(value -> !key.equals(AdvisoryGroup.Key.from(value))))
            throw new AdvisoryConflictException("Advisory group changed; refresh and review the service and direction");
        if (selected.stream().map(AdvisoryProposal::busId).distinct().count() != selected.size())
            throw new AdvisoryConflictException("Advisory group contains multiple proposals for a bus; refresh and review its recipients");
        if (selected.stream().anyMatch(value -> replacements.containsKey(value.id()) && !isMovement(value)))
            throw new IllegalArgumentException("Only a movement instruction can replace a conflicting movement instruction");
        boolean pending = selected.stream().allMatch(value -> "PENDING".equals(value.status()));
        boolean approved = selected.stream().allMatch(value -> "APPROVED".equals(value.status()));
        if (!pending && !approved) throw new AdvisoryConflictException("Advisory group changed; refresh and review its recipients");
        if (pending) {
            var reviewed = new HashSet<>(proposalIds);
            var unreviewed = proposals.findPendingGroupMembers(selected.getFirst().id()).stream()
                    .filter(proposal -> !reviewed.contains(proposal.id())).toList();
            if (!instructions.eligiblePending(key.demoRunId(), unreviewed).isEmpty())
                throw new AdvisoryConflictException("Advisory group recipients changed; refresh and review before sending");
            var replacing = selected.stream().filter(value -> replacements.containsKey(value.id())).toList();
            var activeMovement = activeMovement(key.demoRunId(), replacing.stream()
                    .map(value -> value.bus().busId()).collect(Collectors.toSet()));
            for (var proposal : replacing) {
                var previous = activeMovement.get(proposal.bus().busId());
                if (previous == null || !previous.id().equals(replacements.get(proposal.id())))
                    throw new AdvisoryConflictException("The active instruction for " + proposal.bus().busId()
                            + " changed; review the group again");
            }
        }
        // One outer transaction keeps individual decisions, evidence and deliveries atomic.
        try {
            return selected.stream().map(value -> instructions.decide(value.id(), null, null, actor,
                    replacements.get(value.id()))).toList();
        } catch (IllegalArgumentException invalid) {
            throw new AdvisoryConflictException("A recipient is no longer eligible: " + invalid.getMessage());
        }
    }

    private static boolean isMovement(AdvisoryProposal proposal) {
        return proposal.candidate().type() != InstructionType.BE_CAUTIOUS;
    }

    private Map<String, IssuedInstruction> activeMovement(UUID runId, java.util.Set<String> busIds) {
        if (busIds.isEmpty()) return Map.of();
        var now = clock.instant();
        return issued.findActiveMovementByBusIds(runId, busIds).stream().filter(value -> value.isCurrent(now))
                .collect(Collectors.toMap(IssuedInstruction::busId, Function.identity()));
    }
}
