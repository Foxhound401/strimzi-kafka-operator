/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.upgrade;

import io.skodjob.testframe.resources.KubeResourceManager;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.operator.common.Annotations;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.systemtest.annotations.IsolatedTest;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClients;
import io.strimzi.systemtest.resources.CrdClients;
import io.strimzi.systemtest.resources.crd.KafkaComponents;
import io.strimzi.systemtest.resources.operator.SetupClusterOperator;
import io.strimzi.systemtest.storage.TestStorage;
import io.strimzi.systemtest.templates.crd.KafkaNodePoolTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.RollingUpdateUtils;
import io.strimzi.systemtest.utils.TestKafkaVersion;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;

import static io.strimzi.systemtest.TestTags.KRAFT_UPGRADE;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * This test class contains tests for Kafka upgrade/downgrade from version X to X +/- 1, running in KRaft mode.
 * Metadata for upgrade/downgrade procedure are loaded from kafka-versions.yaml in root dir of this repository.
 */
@Tag(KRAFT_UPGRADE)
public class KRaftKafkaUpgradeDowngradeST extends AbstractKRaftUpgradeST {
    private static final Logger LOGGER = LogManager.getLogger(KRaftKafkaUpgradeDowngradeST.class);
    private final int continuousClientsMessageCount = 300;

    @IsolatedTest
    void testKafkaClusterUpgrade() {
        final TestStorage testStorage = new TestStorage(KubeResourceManager.get().getTestContext());
        List<TestKafkaVersion> sortedVersions = TestKafkaVersion.getSupportedKafkaVersions();

        for (int x = 0; x < sortedVersions.size() - 1; x++) {
            TestKafkaVersion initialVersion = sortedVersions.get(x);
            TestKafkaVersion newVersion = sortedVersions.get(x + 1);

            // If it is an upgrade test we keep the metadata version as the lower version number
            String metadataVersion = initialVersion.metadataVersion();

            runVersionChange(testStorage, initialVersion, newVersion, metadataVersion, 3, 3);
        }

        // ##############################
        // Validate that continuous clients finished successfully
        // ##############################
        ClientUtils.waitForClientsSuccess(testStorage.getNamespaceName(), testStorage.getContinuousConsumerName(), testStorage.getContinuousProducerName(), continuousClientsMessageCount);
        // ##############################
    }

    @IsolatedTest
    void testKafkaClusterDowngrade() {
        final TestStorage testStorage = new TestStorage(KubeResourceManager.get().getTestContext());
        List<TestKafkaVersion> sortedVersions = TestKafkaVersion.getSupportedKafkaVersions();

        for (int x = sortedVersions.size() - 1; x > 0; x--) {
            TestKafkaVersion initialVersion = sortedVersions.get(x);
            TestKafkaVersion newVersion = sortedVersions.get(x - 1);

            // If it is a downgrade then we make sure that we are using the lowest metadataVersion from the whole list
            String metadataVersion = sortedVersions.get(0).metadataVersion();
            runVersionChange(testStorage, initialVersion, newVersion, metadataVersion, 3, 3);
        }

        // ##############################
        // Validate that continuous clients finished successfully
        // ##############################
        ClientUtils.waitForClientsSuccess(testStorage.getNamespaceName(), testStorage.getContinuousConsumerName(), testStorage.getContinuousProducerName(), continuousClientsMessageCount);
        // ##############################
    }

    @IsolatedTest
    void testUpgradeWithNoMetadataVersionSet() {
        final TestStorage testStorage = new TestStorage(KubeResourceManager.get().getTestContext());
        List<TestKafkaVersion> sortedVersions = TestKafkaVersion.getSupportedKafkaVersions();

        for (int x = 0; x < sortedVersions.size() - 1; x++) {
            TestKafkaVersion initialVersion = sortedVersions.get(x);
            TestKafkaVersion newVersion = sortedVersions.get(x + 1);

            runVersionChange(testStorage, initialVersion, newVersion, null, 3, 3);
        }

        // ##############################
        // Validate that continuous clients finished successfully
        // ##############################
        ClientUtils.waitForClientsSuccess(testStorage.getNamespaceName(), testStorage.getContinuousConsumerName(), testStorage.getContinuousProducerName(), continuousClientsMessageCount);
        // ##############################
    }

    @BeforeAll
    void setupEnvironment() {
        SetupClusterOperator
            .getInstance()
            .withDefaultConfiguration()
            .install();
    }

    @SuppressWarnings({"checkstyle:MethodLength"})
    void runVersionChange(TestStorage testStorage, TestKafkaVersion initialVersion, TestKafkaVersion newVersion, String initMetadataVersion, int controllerReplicas, int brokerReplicas) {
        boolean isUpgrade = initialVersion.isUpgrade(newVersion);
        Map<String, String> controllerPods;
        Map<String, String> brokerPods;

        boolean sameMinorVersion = initialVersion.metadataVersion().equals(newVersion.metadataVersion());

        if (CrdClients.kafkaClient().inNamespace(testStorage.getNamespaceName()).withName(CLUSTER_NAME).get() == null) {
            LOGGER.info("Deploying initial Kafka version {} with metadataVersion={}", initialVersion.version(), initMetadataVersion);

            KafkaBuilder kafka = KafkaTemplates.kafka(testStorage.getNamespaceName(), CLUSTER_NAME, brokerReplicas)
                .editMetadata()
                    // This is still needed for upgrade tests. It should be remove once the upgrade tests use
                    // only Strimzi versions that do not require these annotations.
                    // Tracked by https://github.com/strimzi/strimzi-kafka-operator/issues/11690
                    .addToAnnotations(Annotations.ANNO_STRIMZI_IO_NODE_POOLS, "enabled")
                    .addToAnnotations(Annotations.ANNO_STRIMZI_IO_KRAFT, "enabled")
                .endMetadata()
                .editSpec()
                    .editKafka()
                        .withVersion(initialVersion.version())
                        .withConfig(null)
                    .endKafka()
                .endSpec();

            // Do not set metadataVersion if it's not passed to method
            if (initMetadataVersion != null) {
                kafka
                    .editSpec()
                        .editKafka()
                            .withMetadataVersion(initMetadataVersion)
                        .endKafka()
                    .endSpec();
            }

            KubeResourceManager.get().createResourceWithWait(
                KafkaNodePoolTemplates.controllerPoolPersistentStorage(testStorage.getNamespaceName(), CONTROLLER_NODE_NAME, CLUSTER_NAME, controllerReplicas).build(),
                KafkaNodePoolTemplates.brokerPoolPersistentStorage(testStorage.getNamespaceName(), BROKER_NODE_NAME, CLUSTER_NAME, brokerReplicas).build(),
                kafka.build()
            );

            // ##############################
            // Attach clients which will continuously produce/consume messages to/from Kafka brokers during rolling update
            // ##############################
            // Setup topic, which has 3 replicas and 2 min.isr to see if producer will be able to work during rolling update
            KubeResourceManager.get().createResourceWithWait(KafkaTopicTemplates.topic(testStorage.getNamespaceName(), testStorage.getContinuousTopicName(), CLUSTER_NAME, 3, 3, 2).build());
            // 40s is used within TF environment to make upgrade/downgrade more stable on slow env
            String producerAdditionConfiguration = "delivery.timeout.ms=300000\nrequest.timeout.ms=20000";

            KafkaClients kafkaBasicClientJob = ClientUtils.getContinuousPlainClientBuilder(testStorage)
                .withNamespaceName(testStorage.getNamespaceName())
                .withBootstrapAddress(KafkaResources.plainBootstrapAddress(CLUSTER_NAME))
                .withMessageCount(continuousClientsMessageCount)
                .withAdditionalConfig(producerAdditionConfiguration)
                .build();

            KubeResourceManager.get().createResourceWithWait(kafkaBasicClientJob.producerStrimzi());
            KubeResourceManager.get().createResourceWithWait(kafkaBasicClientJob.consumerStrimzi());
            // ##############################
        }

        LOGGER.info("Deployment of initial Kafka version ({}) complete", initialVersion.version());

        String controllerVersionResult = CrdClients.kafkaClient().inNamespace(testStorage.getNamespaceName()).withName(CLUSTER_NAME).get().getStatus().getKafkaVersion();
        LOGGER.info("Pre-change Kafka version: {}", controllerVersionResult);

        controllerPods = PodUtils.podSnapshot(testStorage.getNamespaceName(), controllerSelector);
        brokerPods = PodUtils.podSnapshot(testStorage.getNamespaceName(), brokerSelector);

        LOGGER.info("Updating Kafka CR version field to {}", newVersion.version());

        // Change the version in Kafka CR
        KafkaUtils.replace(testStorage.getNamespaceName(), CLUSTER_NAME, kafka -> {
            kafka.getSpec().getKafka().setVersion(newVersion.version());
        });

        LOGGER.info("Waiting for readiness of new Kafka version ({}) to complete", newVersion.version());

        // Wait for the controllers' version change roll
        controllerPods = RollingUpdateUtils.waitTillComponentHasRolled(testStorage.getNamespaceName(), controllerSelector, controllerReplicas, controllerPods);
        LOGGER.info("1st Controllers roll (image change) is complete");

        // Wait for the brokers' version change roll
        brokerPods = RollingUpdateUtils.waitTillComponentHasRolled(testStorage.getNamespaceName(), brokerSelector, brokerReplicas, brokerPods);
        LOGGER.info("1st Brokers roll (image change) is complete");

        String currentMetadataVersion = CrdClients.kafkaClient().inNamespace(testStorage.getNamespaceName()).withName(CLUSTER_NAME).get().getSpec().getKafka().getMetadataVersion();

        LOGGER.info("Deployment of Kafka ({}) complete", newVersion.version());

        PodUtils.verifyThatRunningPodsAreStable(testStorage.getNamespaceName(), CLUSTER_NAME);

        String controllerPodName = KubeResourceManager.get().kubeClient().listPodsByPrefixInName(testStorage.getNamespaceName(), KafkaComponents.getPodSetName(CLUSTER_NAME, CONTROLLER_NODE_NAME)).get(0).getMetadata().getName();
        String brokerPodName = KubeResourceManager.get().kubeClient().listPodsByPrefixInName(testStorage.getNamespaceName(), KafkaComponents.getPodSetName(CLUSTER_NAME, BROKER_NODE_NAME)).get(0).getMetadata().getName();

        // Extract the Kafka version number from the jars in the lib directory
        controllerVersionResult = KafkaUtils.getVersionFromKafkaPodLibs(testStorage.getNamespaceName(), controllerPodName);
        LOGGER.info("Post-change Kafka version query returned: {}", controllerVersionResult);

        assertThat("Kafka container had version " + controllerVersionResult + " where " + newVersion.version() +
            " was expected", controllerVersionResult, is(newVersion.version()));

        // Extract the Kafka version number from the jars in the lib directory
        String brokerVersionResult = KafkaUtils.getVersionFromKafkaPodLibs(testStorage.getNamespaceName(), brokerPodName);
        LOGGER.info("Post-change Kafka version query returned: {}", brokerVersionResult);

        assertThat("Kafka container had version " + brokerVersionResult + " where " + newVersion.version() +
            " was expected", brokerVersionResult, is(newVersion.version()));

        if (isUpgrade && !sameMinorVersion) {
            LOGGER.info("Updating Kafka config attribute 'metadataVersion' from '{}' to '{}' version", initialVersion.metadataVersion(), newVersion.metadataVersion());

            KafkaUtils.replace(testStorage.getNamespaceName(), CLUSTER_NAME, kafka -> {
                LOGGER.info("Kafka config before updating '{}'", kafka.getSpec().getKafka().toString());

                kafka.getSpec().getKafka().setMetadataVersion(newVersion.metadataVersion());

                LOGGER.info("Kafka config after updating '{}'", kafka.getSpec().getKafka().toString());
            });

            LOGGER.info("Metadata version changed, it doesn't require rolling update, so the Pods should be stable");
            PodUtils.verifyThatRunningPodsAreStable(testStorage.getNamespaceName(), CLUSTER_NAME);
            assertFalse(RollingUpdateUtils.componentHasRolled(testStorage.getNamespaceName(), controllerSelector, controllerPods));
            assertFalse(RollingUpdateUtils.componentHasRolled(testStorage.getNamespaceName(), brokerSelector, brokerPods));
        }

        if (!isUpgrade) {
            LOGGER.info("Verifying that metadataVersion attribute updated correctly to version {}", initMetadataVersion);
            TestUtils.waitFor(String.format("metadataVersion attribute updated correctly to version %s", initMetadataVersion),
                TestConstants.GLOBAL_POLL_INTERVAL, TestConstants.GLOBAL_STATUS_TIMEOUT,
                () -> CrdClients.kafkaClient().inNamespace(testStorage.getNamespaceName()).withName(CLUSTER_NAME)
                    .get().getStatus().getKafkaMetadataVersion().contains(initMetadataVersion));
        } else {
            if (currentMetadataVersion != null) {
                LOGGER.info("Verifying that metadataVersion attribute updated correctly to version {}", newVersion.metadataVersion());
                TestUtils.waitFor(String.format("metadataVersion attribute updated correctly to version %s", newVersion.metadataVersion()),
                    TestConstants.GLOBAL_POLL_INTERVAL, TestConstants.GLOBAL_STATUS_TIMEOUT,
                    () -> CrdClients.kafkaClient().inNamespace(testStorage.getNamespaceName()).withName(CLUSTER_NAME)
                        .get().getStatus().getKafkaMetadataVersion().contains(newVersion.metadataVersion()));
            }
        }

        LOGGER.info("Waiting till Kafka Cluster {}/{} with specified version {} has the same version in status and specification", testStorage.getNamespaceName(), CLUSTER_NAME, newVersion.version());
        KafkaUtils.waitUntilStatusKafkaVersionMatchesExpectedVersion(testStorage.getNamespaceName(), CLUSTER_NAME, newVersion.version());
    }
}
