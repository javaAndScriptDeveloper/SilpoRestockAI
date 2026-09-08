package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The proposal on the table for a group round (task 68), as stored in {@code group_event.proposal_json}.
 *
 * @param version which revision this is; approvals are counted against it
 * @param lines what the group is asked to approve — real catalog products when {@code priced}
 * @param unresolved plain names the catalog had nothing acceptable for
 * @param estimatedTotal the sum of the priced lines, or null when nothing is priced
 * @param priced whether the lines were grounded in the organizer's Silpo catalog
 * @param note the model's one sentence for the group
 * @param preferences the per-participant durable preferences the model extracted this round
 */
public record GroupProposal(
        int version,
        List<GroupProposalLine> lines,
        List<String> unresolved,
        BigDecimal estimatedTotal,
        boolean priced,
        String note,
        List<ParticipantPreference> preferences) {

    /** Lines a cart can actually be built from. */
    public List<GroupProposalLine> resolvedLines() {
        return lines == null
                ? List.of()
                : lines.stream().filter(GroupProposalLine::resolved).toList();
    }
}
