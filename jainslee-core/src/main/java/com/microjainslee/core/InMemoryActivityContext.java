package com.microjainslee.core;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SbbLocalObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JBoss-free activity context used by the embedded micro-container.
 */
public final class InMemoryActivityContext implements ActivityContextInterface {

    private final String name;
    private final CopyOnWriteArrayList<SbbLocalObject> attachedSbbs =
            new CopyOnWriteArrayList<SbbLocalObject>();

    public InMemoryActivityContext(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Activity context name must not be empty");
        }
        this.name = name;
    }

    @Override
    public String getActivityContextName() {
        return name;
    }

    @Override
    public void attach(SbbLocalObject sbbLocalObject) {
        if (sbbLocalObject != null && !attachedSbbs.contains(sbbLocalObject)) {
            attachedSbbs.add(sbbLocalObject);
        }
    }

    @Override
    public void detach(SbbLocalObject sbbLocalObject) {
        attachedSbbs.remove(sbbLocalObject);
    }

    public List<SbbLocalObject> getAttachedSbbs() {
        return Collections.unmodifiableList(new ArrayList<SbbLocalObject>(attachedSbbs));
    }
}
