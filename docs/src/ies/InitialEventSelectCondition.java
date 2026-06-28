package com.microjainslee.core.ies;

import com.microjainslee.api.ActivityContextInterface;

/**
 * Condition object passed to IES method trên SBB.
 * Chứa incoming event và ACI context để SBB derive convergence name.
 *
 * Ví dụ USSD SBB:
 *   @InitialEventSelect
 *   public InitialEventSelectResult selectInitialEvent(InitialEventSelectCondition c) {
 *       UssdEvent e = (UssdEvent) c.getEvent();
 *       return InitialEventSelectResult.builder()
 *           .convergenceName(e.getMsisdn() + ":" + e.getDialogId())
 *           .initialEvent(e.getType() == UssdType.BEGIN)
 *           .build();
 *   }
 */
public record InitialEventSelectCondition(
        Object event,
        ActivityContextInterface aci
) {
    public Object getEvent() { return event; }
    public ActivityContextInterface getAci() { return aci; }
}
