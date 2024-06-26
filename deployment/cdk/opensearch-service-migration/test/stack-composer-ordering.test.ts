import {createStackComposer} from "./test-utils";
import {Template} from "aws-cdk-lib/assertions";
import {CaptureProxyESStack} from "../lib/service-stacks/capture-proxy-es-stack";
import {CaptureProxyStack} from "../lib/service-stacks/capture-proxy-stack";
import {ElasticsearchStack} from "../lib/service-stacks/elasticsearch-stack";
import {TrafficReplayerStack} from "../lib/service-stacks/traffic-replayer-stack";
import {MigrationConsoleStack} from "../lib/service-stacks/migration-console-stack";
import {KafkaStack} from "../lib/service-stacks/kafka-stack";
import {ContainerImage} from "aws-cdk-lib/aws-ecs";
import {OpenSearchContainerStack} from "../lib/service-stacks/opensearch-container-stack";
import {OtelCollectorStack} from "../lib/service-stacks/migration-otel-collector-stack";
import {ReindexFromSnapshotStack} from "../lib/service-stacks/reindex-from-snapshot-stack";

// Mock using local Dockerfile (which may not exist and would fail synthesis) with the intent of using a "fake-image" from a public registry
jest.mock("aws-cdk-lib/aws-ecr-assets")
jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("fake-image"));

test('Test all migration services with MSK get created when enabled', () => {

    const contextOptions = {
        "stage": "test",
        "engineVersion": "OS_2.9",
        "domainName": "unit-test-opensearch-cluster",
        "dataNodeCount": 2,
        "openAccessPolicyEnabled": true,
        "domainRemovalPolicy": "DESTROY",
        "vpcEnabled": true,
        "migrationAssistanceEnabled": true,
        "migrationConsoleServiceEnabled": true,
        "captureProxyESServiceEnabled": true,
        "trafficReplayerServiceEnabled": true,
        "captureProxyServiceEnabled": true,
        "elasticsearchServiceEnabled": true,
        "otelCollectorEnabled": true,
        "osContainerServiceEnabled": true,
        "reindexFromSnapshotServiceEnabled": true
    }

    const stacks = createStackComposer(contextOptions)

    const services = [CaptureProxyESStack, CaptureProxyStack, ElasticsearchStack, MigrationConsoleStack,
        TrafficReplayerStack, OpenSearchContainerStack, OtelCollectorStack, ReindexFromSnapshotStack]
    services.forEach( (stackClass) => {
        const stack = stacks.stacks.filter((s) => s instanceof stackClass)[0]
        const template = Template.fromStack(stack)
        template.resourceCountIs("AWS::ECS::Service", 1)
    })
})

test('Test all migration services with Kafka container get created when enabled', () => {

    const contextOptions = {
        "stage": "test",
        "engineVersion": "OS_2.9",
        "domainName": "unit-test-opensearch-cluster",
        "dataNodeCount": 2,
        "openAccessPolicyEnabled": true,
        "domainRemovalPolicy": "DESTROY",
        "vpcEnabled": true,
        "migrationAssistanceEnabled": true,
        "migrationConsoleServiceEnabled": true,
        "captureProxyESServiceEnabled": true,
        "trafficReplayerServiceEnabled": true,
        "captureProxyServiceEnabled": true,
        "elasticsearchServiceEnabled": true,
        "kafkaBrokerServiceEnabled": true,
        "otelCollectorEnabled": true,
        "osContainerServiceEnabled": true,
        "reindexFromSnapshotServiceEnabled": true
    }

    const stacks = createStackComposer(contextOptions)

    const services = [CaptureProxyESStack, CaptureProxyStack, ElasticsearchStack, MigrationConsoleStack,
        TrafficReplayerStack, KafkaStack, OpenSearchContainerStack, OtelCollectorStack, ReindexFromSnapshotStack]
    services.forEach( (stackClass) => {
        const stack = stacks.stacks.filter((s) => s instanceof stackClass)[0]
        const template = Template.fromStack(stack)
        template.resourceCountIs("AWS::ECS::Service", 1)
    })
})

test('Test no migration services get deployed when disabled', () => {

    const contextOptions = {
        "stage": "test",
        "engineVersion": "OS_2.9",
        "domainName": "unit-test-opensearch-cluster",
        "dataNodeCount": 2,
        "openAccessPolicyEnabled": true,
        "domainRemovalPolicy": "DESTROY",
        "vpcEnabled": true,
        "migrationAssistanceEnabled": true,
        "migrationConsoleServiceEnabled": false,
        "captureProxyESServiceEnabled": false,
        "trafficReplayerServiceEnabled": false,
        "captureProxyServiceEnabled": false,
        "elasticsearchServiceEnabled": false,
        "kafkaBrokerServiceEnabled": false,
        "otelCollectorEnabled": false,
        "osContainerServiceEnabled": false,
        "reindexFromSnapshotServiceEnabled": false
    }

    const stacks = createStackComposer(contextOptions)

    const services = [CaptureProxyESStack, CaptureProxyStack, ElasticsearchStack, MigrationConsoleStack,
        TrafficReplayerStack, KafkaStack, OpenSearchContainerStack, OtelCollectorStack, ReindexFromSnapshotStack]
    services.forEach( (stackClass) => {
        const stack = stacks.stacks.filter((s) => s instanceof stackClass)[0]
        expect(stack).toBeUndefined()
    })

})