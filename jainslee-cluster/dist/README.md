# ClusterWitness dist (ADR 0007 D12)

Third JGroups member for a two-node active/active pair. Runs **no RA, holds no
socket, owns no dialog** — it joins the cluster and defines the same fenced
lease caches (`ra-<name>-lease`, `DENY_READ_WRITES`), which is what makes it
count as a vote. Whichever SS7 node still sees the witness keeps the majority
and may take dialogs over; the minority side stops transmitting.

## Package

```bash
cd jainslee-cluster
./dist/build-witness-dist.sh        # JDK 25 only (mise zulu-25)
# → dist/cluster-witness/{run.sh,cluster-witness.jar,lib/,configs/log4j2.xml,logs/}
```

Runtime bits only: thin module jar + `lib/` (jainslee-api, jainslee-core,
infinispan-core, jgroups, log4j-api/core). New code is
`org.apache.logging.log4j` only.

## Run

```bash
MICROJANSLEE_NODE_ID=witness-1 \
MICROJANSLEE_STACK=tcp \
MICROJANSLEE_INITIAL_HOSTS='ss7-1[7800],ss7-2[7800],witness-1[7800]' \
MICROJANSLEE_WITNESS_RA_NAMES=ra-jss7 \
./dist/cluster-witness/run.sh
```

Expected: `Cluster witness up node=witness-1 voting on [ra-jss7]`, and a view
of 3 members on every node. `ra-names` must list the RA names whose leases the
witness votes on (the RA's `raName`, default `ra-jss7`).

## systemd

```bash
sudo install -m 0644 dist/cluster-witness@.service /etc/systemd/system/
# /opt/microjainslee/witness-<id>/{run.sh,cluster-witness.jar,lib/,configs/,logs/,witness.env}
sudo systemctl daemon-reload
sudo systemctl enable --now cluster-witness@witness-1
```

`witness.env` (optional, overrides the unit defaults):

```bash
MICROJANSLEE_NODE_ID=witness-1
MICROJANSLEE_STACK=tcp
MICROJANSLEE_INITIAL_HOSTS=ss7-1[7800],ss7-2[7800],witness-1[7800]
MICROJANSLEE_WITNESS_RA_NAMES=ra-jss7
```
