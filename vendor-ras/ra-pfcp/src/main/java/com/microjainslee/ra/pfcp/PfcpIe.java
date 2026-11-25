package com.microjainslee.ra.pfcp;

import java.util.Objects;

/**
 * TS 29.244 Information Element. The four-octet TLV header is a two-octet
 * type followed by a two-octet length (TS 29.244 §8.1.1).
 */
public record PfcpIe(int type, byte[] value) {
    public PfcpIe {
        Objects.requireNonNull(value, "value");
        if (type < 0 || type > 65_535) {
            throw new IllegalArgumentException("IE type must be 0..65535");
        }
        if (value.length > 65_535) {
            throw new IllegalArgumentException("IE value must fit the 16-bit length");
        }
    }

    public static final int CREATE_PDR = 1;
    public static final int PDI = 2;
    public static final int CREATE_FAR = 3;
    public static final int FORWARDING_PARAMETERS = 4;
    public static final int CREATE_URR = 6;
    public static final int CREATE_QER = 7;
    public static final int CREATED_PDR = 8;
    public static final int UPDATE_FAR = 10;
    public static final int UPDATE_FORWARDING_PARAMETERS = 11;
    public static final int REMOVE_PDR = 15;
    public static final int REMOVE_FAR = 16;
    public static final int CAUSE = 19;
    public static final int SOURCE_INTERFACE = 20;
    public static final int F_TEID = 21;
    public static final int NETWORK_INSTANCE = 22;
    public static final int GATE_STATUS = 25;
    public static final int MBR = 26;
    public static final int PRECEDENCE = 29;
    public static final int VOLUME_THRESHOLD = 31;
    public static final int TIME_THRESHOLD = 32;
    public static final int REPORTING_TRIGGERS = 37;
    public static final int REPORT_TYPE = 39;
    public static final int OFFENDING_IE = 40;
    public static final int DESTINATION_INTERFACE = 42;
    public static final int APPLY_ACTION = 44;
    public static final int DOWNLINK_DATA_NOTIFICATION_DELAY = 46;
    public static final int PDR_ID = 56;
    public static final int F_SEID = 57;
    public static final int NODE_ID = 60;
    public static final int MEASUREMENT_METHOD = 62;
    public static final int USAGE_REPORT_TRIGGER = 63;
    public static final int VOLUME_MEASUREMENT = 66;
    public static final int DURATION_MEASUREMENT = 67;
    public static final int QUERY_URR = 77;
    public static final int USAGE_REPORT_SMR = 78;
    public static final int USAGE_REPORT_SDR = 79;
    public static final int USAGE_REPORT_SRR = 80;
    public static final int URR_ID = 81;
    public static final int DOWNLINK_DATA_REPORT = 83;
    public static final int OUTER_HEADER_CREATION = 84;
    public static final int CREATE_BAR = 85;
    public static final int BAR_ID = 88;
    public static final int UE_IP_ADDRESS = 93;
    public static final int OUTER_HEADER_REMOVAL = 95;
    public static final int RECOVERY_TIME_STAMP = 96;
    public static final int UR_SEQN = 104;
    public static final int FAR_ID = 108;
    public static final int QER_ID = 109;

    /** Source/Destination Interface values (TS 29.244 §8.2.2 / §8.2.24). */
    public static final int INTERFACE_ACCESS = 0;
    public static final int INTERFACE_CORE = 1;
    public static final int INTERFACE_SGI_N6_LAN = 2;
    public static final int INTERFACE_CP_FUNCTION = 3;

    /** Apply Action bits (TS 29.244 §8.2.26). */
    public static final int APPLY_DROP = 1;
    public static final int APPLY_FORW = 2;
    public static final int APPLY_BUFF = 4;
    public static final int APPLY_NOCP = 8;

    /** Measurement Method bits (TS 29.244 §8.2.40). */
    public static final int MM_DURATION = 1;
    public static final int MM_VOLUME = 2;
    public static final int MM_EVENT = 4;

    /** Usage Reporting Trigger bits (TS 29.244 §8.2.19). */
    public static final int UT_PERIO = 1;
    public static final int UT_VOLTH = 2;
    public static final int UT_TIMTH = 4;
    public static final int UT_QUHTI = 8;

    /** Report Type bits (TS 29.244 §8.2.21). */
    public static final int REPORT_TYPE_DLDR = 1;
    public static final int REPORT_TYPE_USAR = 2;

    /** Volume Measurement/Threshold flags (TS 29.244 §8.2.44 / §8.2.13). */
    public static final int VM_TOVOL = 1;
    public static final int VM_ULVOL = 2;
    public static final int VM_DLVOL = 4;

    /** Cause: request accepted (TS 29.244 §8.2.1). */
    public static final int CAUSE_REQUEST_ACCEPTED = 1;
}
