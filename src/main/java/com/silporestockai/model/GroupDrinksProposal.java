package com.silporestockai.model;

import java.util.List;

/**
 * What Claude answers for a group round (task 68): the drink lines, a preference summary per participant, and one
 * sentence for the group. Synthesised from gathered facts, never from a blank prompt.
 */
public record GroupDrinksProposal(List<GroupDrinkLine> lines, List<ParticipantPreference> participants, String note) {}
