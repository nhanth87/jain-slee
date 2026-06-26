/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §13 — Service identifier.
 * Uniquely identifies a deployed service within the SLEE.
 */
public final class ServiceID {

    private final String name;
    private final String vendor;
    private final String version;

    public ServiceID(String name, String vendor, String version) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("service name is required");
        }
        if (vendor == null || vendor.trim().isEmpty()) {
            throw new IllegalArgumentException("service vendor is required");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("service version is required");
        }
        this.name = name;
        this.vendor = vendor;
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public String getVendor() {
        return vendor;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ServiceID)) {
            return false;
        }
        ServiceID other = (ServiceID) obj;
        return name.equals(other.name)
                && vendor.equals(other.vendor)
                && version.equals(other.version);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + vendor.hashCode();
        result = 31 * result + version.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return vendor + "/" + name + "/" + version;
    }
}
