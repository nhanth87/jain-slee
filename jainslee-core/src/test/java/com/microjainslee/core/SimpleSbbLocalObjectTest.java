/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.ServiceID;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SimpleSbbLocalObjectTest {

    @Test
    public void removeMarksEntityRemovedAndInvokesListener() {
        RecordingSbb sbb = new RecordingSbb();
        AtomicBoolean removed = new AtomicBoolean(false);
        SimpleSbbLocalObject localObject = new SimpleSbbLocalObject(
                new SbbID("child"),
                sbb,
                null,
                new SimpleSbbLocalObject.RemovalListener() {
                    @Override
                    public void onRemoved(SimpleSbbLocalObject object) {
                        removed.set(true);
                    }
                },
                3);

        assertEquals(3, localObject.getPriority());
        assertFalse(localObject.isRemoved());

        localObject.remove();

        assertTrue(localObject.isRemoved());
        assertTrue(sbb.removed);
        assertTrue(removed.get());
    }

    @Test
    public void invokeLocallyRoutesThroughEntityPoolThread() throws Exception {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(0, 2, false);
        RecordingSbb sbb = new RecordingSbb();
        SimpleSbbLocalObject localObject = new SimpleSbbLocalObject(
                new SbbID("routed"),
                sbb,
                pool,
                null,
                0);
        pool.acquire("routed", new java.util.function.Supplier<Sbb>() {
            @Override
            public Sbb get() {
                return sbb;
            }
        });

        final AtomicReference<String> threadName = new AtomicReference<String>();
        localObject.invokeLocally(new Runnable() {
            @Override
            public void run() {
                threadName.set(Thread.currentThread().getName());
            }
        });

        assertTrue(threadName.get() != null);
        pool.shutdown();
    }

    @Test
    public void simpleSbbContextExposesServiceAndLocalObject() {
        ServiceID serviceID = new ServiceID("svc", "com.example", "1.0");
        SbbLocalObject localObject = new SimpleSbbLocalObject(new SbbID("id"), new RecordingSbb());
        SimpleSbbContext context = new SimpleSbbContext(
                serviceID,
                localObject,
                TimerPortImpl.create(new EventRouter(8)),
                new InMemoryActivityContextNamingFacility());

        assertSame(serviceID, context.getService());
        assertSame(localObject, context.getSbbLocalObject());
    }

    private static final class RecordingSbb implements Sbb {
        private boolean removed;

        @Override
        public void sbbRemove() {
            removed = true;
        }
    }
}
