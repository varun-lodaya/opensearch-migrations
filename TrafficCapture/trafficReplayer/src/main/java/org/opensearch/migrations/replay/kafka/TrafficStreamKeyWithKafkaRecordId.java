package org.opensearch.migrations.replay.kafka;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.tracing.Contexts;
import org.opensearch.migrations.replay.tracing.IChannelKeyContext;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.util.StringJoiner;
import java.util.function.Function;

@EqualsAndHashCode(callSuper = true)
@Getter
class TrafficStreamKeyWithKafkaRecordId extends PojoTrafficStreamKey implements KafkaCommitOffsetData {
    public static final String TELEMETRY_SCOPE_NAME = "KafkaRecords";
    public static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure(TELEMETRY_SCOPE_NAME);

    private final int generation;
    private final int partition;
    private final long offset;

    TrafficStreamKeyWithKafkaRecordId(Function<ITrafficStreamKey, IChannelKeyContext> contextFactory,
                                      TrafficStream trafficStream, String recordId, KafkaCommitOffsetData ok) {
        this(contextFactory, trafficStream, recordId, ok.getGeneration(), ok.getPartition(), ok.getOffset());
    }

    TrafficStreamKeyWithKafkaRecordId(Function<ITrafficStreamKey, IChannelKeyContext> contextFactory,
                                      TrafficStream trafficStream, String recordId,
                                      int generation, int partition, long offset) {
        super(trafficStream);
        this.generation = generation;
        this.partition = partition;
        this.offset = offset;
        var channelKeyContext = contextFactory.apply(this);
        var kafkaContext = new Contexts.KafkaRecordContext(channelKeyContext, recordId,
                METERING_CLOSURE.makeSpanContinuation("kafkaRecord"));
        this.setTrafficStreamsContext(new Contexts.TrafficStreamsLifecycleContext(kafkaContext, this,
                METERING_CLOSURE.makeSpanContinuation("trafficStreamLifecycle")));
    }

    @Override
    public String toString() {
        return new StringJoiner("|")
                .add(super.toString())
                .add("partition=" + partition)
                .add("offset=" + offset)
                .toString();
    }
}
