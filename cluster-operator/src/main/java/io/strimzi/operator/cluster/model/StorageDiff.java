/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.zjsonpatch.JsonDiff;
import io.strimzi.api.kafka.model.kafka.JbodStorage;
import io.strimzi.api.kafka.model.kafka.PersistentClaimStorage;
import io.strimzi.api.kafka.model.kafka.PersistentClaimStorageOverride;
import io.strimzi.api.kafka.model.kafka.SingleVolumeStorage;
import io.strimzi.api.kafka.model.kafka.Storage;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.model.AbstractJsonDiff;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

/**
 * Class for diffing storage configuration
 */
public class StorageDiff extends AbstractJsonDiff {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(StorageDiff.class.getName());

    private static final Pattern IGNORABLE_PATHS = Pattern.compile(
            "^(/deleteClaim|/kraftMetadata|/)$");

    private final boolean isEmpty;
    private final boolean changesType;
    private final boolean shrinkSize;
    private final boolean volumesAddedOrRemoved;
    private final boolean tooManyKRaftMetadataVolumes;
    private final boolean duplicateVolumeIds;

    /**
     * Diffs the storage for allowed or not allowed changes. Examples of allowed changes is increasing volume size or
     * adding overrides for nodes before scale-up / removing them after scale-down.
     *
     * @param reconciliation    The reconciliation
     * @param current           Current Storage configuration
     * @param desired           Desired Storage configuration
     * @param currentNodeIds    Node IDs currently used by this node pool
     * @param desiredNodeIds    Node IDs used in the future by this node pool
     */
    public StorageDiff(Reconciliation reconciliation, Storage current, Storage desired, Set<Integer> currentNodeIds, Set<Integer> desiredNodeIds) {
        this(reconciliation, current, desired, currentNodeIds, desiredNodeIds, "");
    }

    /**
     * Diffs the storage for allowed or not allowed changes. Examples of allowed changes is increasing volume size or
     * adding overrides for nodes before scale-up / removing them after scale-down. This constructor is used internally
     * only.
     *
     * @param reconciliation    The reconciliation
     * @param current           Current Storage configuration
     * @param desired           Desired Storage configuration
     * @param currentNodeIds    Node IDs currently used by this node pool
     * @param desiredNodeIds    Node IDs used in the future by this node pool
     * @param volumeDesc        Description of the volume which is being used
     */
    private StorageDiff(Reconciliation reconciliation, Storage current, Storage desired, Set<Integer> currentNodeIds, Set<Integer> desiredNodeIds, String volumeDesc) {
        boolean changesType = false;
        boolean shrinkSize = false;
        boolean isEmpty = true;
        boolean volumesAddedOrRemoved = false;
        boolean tooManyKRaftMetadataVolumes = false;
        boolean duplicateVolumeIds = false;

        if (current instanceof JbodStorage currentJbodStorage && desired instanceof JbodStorage desiredJbodStorage) {
            Set<Integer> volumeIds = new HashSet<>();

            volumeIds.addAll(currentJbodStorage.getVolumes().stream().map(SingleVolumeStorage::getId).collect(Collectors.toSet()));
            volumeIds.addAll(desiredJbodStorage.getVolumes().stream().map(SingleVolumeStorage::getId).collect(Collectors.toSet()));

            // Check if there multiple JBOD volumes marked for KRaft metadata
            tooManyKRaftMetadataVolumes = checkTooManyKRaftMetadataVolumes(reconciliation, desiredJbodStorage);

            // Check for duplicate volume IDs
            duplicateVolumeIds = checkDuplicateIDs(reconciliation, desiredJbodStorage);

            // Check for configuration differences
            for (Integer volumeId : volumeIds)  {
                SingleVolumeStorage currentVolume = currentJbodStorage.getVolumes().stream()
                        .filter(volume -> volume != null && volumeId.equals(volume.getId()))
                        .findAny().orElse(null);
                SingleVolumeStorage desiredVolume = desiredJbodStorage.getVolumes().stream()
                        .filter(volume -> volume != null && volumeId.equals(volume.getId()))
                        .findAny().orElse(null);

                volumesAddedOrRemoved |= isNull(currentVolume) != isNull(desiredVolume);

                StorageDiff diff = new StorageDiff(reconciliation, currentVolume, desiredVolume, currentNodeIds, desiredNodeIds, "(volume ID: " + volumeId + ") ");

                changesType |= diff.changesType();
                shrinkSize |= diff.shrinkSize();
                isEmpty &= diff.isEmpty();
            }
        } else {
            JsonNode source = PATCH_MAPPER.valueToTree(current == null ? "{}" : current);
            JsonNode target = PATCH_MAPPER.valueToTree(desired == null ? "{}" : desired);
            JsonNode diff = JsonDiff.asJson(source, target);

            int num = 0;

            for (JsonNode d : diff) {
                String pathValue = d.get("path").asText();

                if (IGNORABLE_PATHS.matcher(pathValue).matches()) {
                    LOGGER.debugCr(reconciliation, "Ignoring Storage {}diff {}", volumeDesc, d);
                    continue;
                }

                // It might be possible to increase the volume size, but never to shrink volumes
                // When size changes, we need to detect whether it is shrinking or increasing
                if (pathValue.endsWith("/size") && desired.getType().equals(current.getType()) && current instanceof PersistentClaimStorage persistentCurrent && desired instanceof PersistentClaimStorage persistentDesired)    {

                    long currentSize = StorageUtils.convertToMillibytes(persistentCurrent.getSize());
                    long desiredSize = StorageUtils.convertToMillibytes(persistentDesired.getSize());

                    if (currentSize > desiredSize) {
                        shrinkSize = true;
                    } else {
                        continue;
                    }
                }

                // Some changes to overrides are allowed:
                // * When scaling up or down, you can set the overrides for new nodes
                // * You can set overrides for nodes which do nto exist (yet)
                if (pathValue.startsWith("/overrides")) {
                    if (isOverrideChangeAllowed(current, desired, currentNodeIds, desiredNodeIds))    {
                        continue;
                    }
                }

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugCr(reconciliation, "Storage {}differs: {}", volumeDesc, d);
                    LOGGER.debugCr(reconciliation, "Current Storage {}path {} has value {}", volumeDesc, pathValue, lookupPath(source, pathValue));
                    LOGGER.debugCr(reconciliation, "Desired Storage {}path {} has value {}", volumeDesc, pathValue, lookupPath(target, pathValue));
                }

                num++;
                changesType |= pathValue.endsWith("/type");
            }

            isEmpty = num == 0;
        }

        this.isEmpty = isEmpty;
        this.changesType = changesType;
        this.shrinkSize = shrinkSize;
        this.volumesAddedOrRemoved = volumesAddedOrRemoved;
        this.tooManyKRaftMetadataVolumes = tooManyKRaftMetadataVolumes;
        this.duplicateVolumeIds = duplicateVolumeIds;
    }

    /**
     * Checks JBOD storage for multiple volumes marked to store KRaft metadata.
     *
     * @param reconciliation        Reconciliation marker
     * @param desiredJbodStorage    Desired JBOD storage configuration
     *
     * @return  True if there are multiple volumes marked for KRaft metadata. False otherwise.
     */
    private static boolean checkTooManyKRaftMetadataVolumes(Reconciliation reconciliation, JbodStorage desiredJbodStorage)   {
        boolean tooManyKRaftMetadataVolumes = desiredJbodStorage.getVolumes().stream().filter(v -> v.getKraftMetadata() != null).count() > 1;

        if (tooManyKRaftMetadataVolumes)    {
            LOGGER.debugCr(reconciliation, "Multiple volumes of a Jbod storage are configured to store Kraft metadata logs");
        }

        return tooManyKRaftMetadataVolumes;
    }

    /**
     * Checks JBOD storage for duplicate volume IDs.
     *
     * @param reconciliation        Reconciliation marker
     * @param desiredJbodStorage    Desired JBOD storage configuration
     *
     * @return  True if there are any duplicate IDs. False otherwise.
     */
    private static boolean checkDuplicateIDs(Reconciliation reconciliation, JbodStorage desiredJbodStorage) {
        boolean duplicateVolumeIds = desiredJbodStorage.getVolumes().stream().map(SingleVolumeStorage::getId).distinct().count() != desiredJbodStorage.getVolumes().size();

        if (duplicateVolumeIds) {
            LOGGER.debugCr(reconciliation, "Duplicate volume IDs found in the desired Jbod storage configuration");
        }

        return duplicateVolumeIds;
    }

    /**
     * Returns whether the Diff is empty or not
     *
     * @return true when the storage configurations are the same
     */
    @Override
    public boolean isEmpty() {
        return isEmpty;
    }

    /**
     * Returns true if there's a difference in {@code /type}
     *
     * @return true when the storage configurations have different type
     */
    protected boolean changesType() {
        return changesType;
    }

    /**
     * Returns true if there's a difference in {@code /size}
     *
     * @return true when the size of the volumes changed
     */
    protected boolean shrinkSize() {
        return shrinkSize;
    }

    /**
     * Returns true if some JBOD volumes were added or removed
     *
     * @return true when volumes were added or removed
     */
    protected boolean isVolumesAddedOrRemoved() {
        return volumesAddedOrRemoved;
    }

    /**
     * Indicates whether too many volumes of a JBOD storage are marked as KRaft metadata volumes
     *
     * @return true when multiple volumes are marked as KRaft metadata volumes. False if no or only one volume is marked.
     */
    protected boolean isTooManyKRaftMetadataVolumes() {
        return tooManyKRaftMetadataVolumes;
    }

    /**
     * Indicates whether there are multiple JBOD volumes with the same ID.
     *
     * @return True when multiple volumes have the same ID. False otherwise.
     */
    protected boolean isDuplicateVolumeIds() {
        return duplicateVolumeIds;
    }

    /**
     * Indicates whether any issues with the storage configuration were detected. This included forbidden changes,
     * duplicate volume IDs, or multiple volumes marked for storing KRaft metadata.
     *
     * @return True when issues with the storage configuration change were detected. False otherwise.
     */
    public boolean issuesDetected() {
        return !isEmpty || duplicateVolumeIds || tooManyKRaftMetadataVolumes;
    }

    /**
     * Validates the changes to the storage overrides and decides whether they are allowed or not. Allowed changes are
     * those to nodes which will be added, removed or which do nto exist yet.
     *
     * @param current           Current Storage configuration
     * @param desired           New storage configuration
     * @param currentNodeIds    Node IDs currently used by this node pool
     * @param desiredNodeIds    Node IDs used in the future by this node pool
     *
     * @return                  True if only allowed override changes were done, false otherwise
     */
    @SuppressWarnings("deprecation") // Storage overrides are deprecated
    private boolean isOverrideChangeAllowed(Storage current, Storage desired, Set<Integer> currentNodeIds, Set<Integer> desiredNodeIds)   {
        List<PersistentClaimStorageOverride> currentOverrides = ((PersistentClaimStorage) current).getOverrides();
        if (currentOverrides == null)   {
            currentOverrides = Collections.emptyList();
        }

        List<PersistentClaimStorageOverride> desiredOverrides = ((PersistentClaimStorage) desired).getOverrides();
        if (desiredOverrides == null)   {
            desiredOverrides = Collections.emptyList();
        }

        // We care only about the nodes which existed before this reconciliation and will still exist after it
        Set<Integer> existedAndWillExist = new TreeSet<>(currentNodeIds);
        existedAndWillExist.retainAll(desiredNodeIds);

        for (int nodeId : existedAndWillExist)   {
            PersistentClaimStorageOverride currentOverride = currentOverrides.stream()
                    .filter(override -> override.getBroker() == nodeId)
                    .findFirst()
                    .orElse(null);

            PersistentClaimStorageOverride desiredOverride = desiredOverrides.stream()
                    .filter(override -> override.getBroker() == nodeId)
                    .findFirst()
                    .orElse(null);

            if (currentOverride != null && desiredOverride != null) {
                // Both overrides exist but are not equal
                if (!currentOverride.equals(desiredOverride)) {
                    return false;
                }
            } else if (currentOverride != null || desiredOverride != null) {
                // One of them is null while the other is not null => they differ
                return false;
            }
        }

        return true;
    }
}
