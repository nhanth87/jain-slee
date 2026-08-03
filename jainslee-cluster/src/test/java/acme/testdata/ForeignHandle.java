/*
 * micro-jainslee 1.2.0 — test fixture outside allow-list packages.
 */
package acme.testdata;

import java.io.Serializable;

/** Deliberately outside com.microjainslee.* / com.example.* / java.*. */
public final class ForeignHandle implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String id;

    public ForeignHandle(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
