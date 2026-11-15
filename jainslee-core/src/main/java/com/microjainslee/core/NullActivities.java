/*
 * micro-jainslee
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 */
package com.microjainslee.core;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.NullActivity;
import com.microjainslee.api.SleeEvent;

/**
 * Raise one application event on its own JAIN SLEE Null Activity (JSR 240 §7.10):
 * create the activity, fire the event on it, end it.
 *
 * <p>Use it whenever application code starts work by itself instead of reacting to a resource
 * adaptor — sending a request to a server, pushing a notification, kicking off a campaign hit.
 * Ending the activity matters: the SLEE then frees the SBB entity and the activity name created
 * for this event. Without the end, each such event leaks both.</p>
 */
public final class NullActivities {

    private NullActivities() { }

    /**
     * @param name  activity name; must not be the name a resource adaptor uses for the reply
     *              to this work, or both would share — and lose — one SBB entity
     */
    public static void fire(MicroSleeContainer container, String name, SleeEvent event) {
        ActivityContextInterface aci = container.getNullActivityFactory().createNullActivity(name);
        container.routeEvent(event, aci);
        ((NullActivity) aci.getActivity()).endActivity();
    }
}
