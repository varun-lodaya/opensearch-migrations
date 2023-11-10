package org.opensearch.migrations.replay;

import com.google.common.base.Strings;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.slf4j.event.Level;
import org.testcontainers.shaded.org.apache.commons.io.output.NullOutputStream;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class TrafficReplayerRunner {

    @AllArgsConstructor
    static class FabricatedErrorToKillTheReplayer extends Error {
        public final boolean doneWithTest;
    }

    private TrafficReplayerRunner() {}

    static void runReplayerUntilSourceWasExhausted(int numExpectedRequests, URI endpoint,
                                                   Supplier<Consumer<SourceTargetCaptureTuple>> tupleReceiverSupplier,
                                                   Supplier<ISimpleTrafficCaptureSource> trafficSourceSupplier)
            throws Throwable {
        AtomicInteger runNumberRef = new AtomicInteger();
        var totalUniqueEverReceived = new AtomicInteger();

        var receivedPerRun = new ArrayList<Integer>();
        var totalUniqueEverReceivedSizeAfterEachRun = new ArrayList<Integer>();
        var completelyHandledItems = new ConcurrentHashMap<String, SourceTargetCaptureTuple>();

        for (; true; runNumberRef.incrementAndGet()) {
            int runNumber = runNumberRef.get();
            var counter = new AtomicInteger();
            var tupleReceiver = tupleReceiverSupplier.get();
            try {
                runTrafficReplayer(trafficSourceSupplier, endpoint, (t) -> {
                    if (runNumber != runNumberRef.get()) {
                        // for an old replayer.  I'm not sure why shutdown isn't blocking until all threads are dead,
                        // but that behavior only impacts this test as far as I can tell.
                        return;
                    }
                    Assertions.assertEquals(runNumber, runNumberRef.get());
                    var key = t.uniqueRequestKey;
                    ISourceTrafficChannelKey tsk = key.getTrafficStreamKey();
                    var keyString = tsk.getConnectionId() + "_" + key.getSourceRequestIndex();
                    tupleReceiver.accept(t);
                    var totalUnique = null != completelyHandledItems.put(keyString, t) ?
                            totalUniqueEverReceived.get() :
                            totalUniqueEverReceived.incrementAndGet();

                    var c = counter.incrementAndGet();
                    log.info("counter="+c+" totalUnique="+totalUnique+" runNum="+runNumber+" key="+key);
                });
                // if this finished running without an exception, we need to stop the loop
                break;
            } catch (TrafficReplayer.TerminationException e) {
                log.atLevel(e.originalCause instanceof FabricatedErrorToKillTheReplayer ? Level.INFO : Level.ERROR)
                        .setCause(e.originalCause)
                        .setMessage(()->"broke out of the replayer, with this shutdown reason")
                        .log();
                log.atLevel(e.immediateCause == null ? Level.INFO : Level.ERROR)
                        .setCause(e.immediateCause)
                        .setMessage(()->"broke out of the replayer, with the shutdown cause=" + e.originalCause +
                                " and this immediate reason")
                        .log();
                if (!(e.originalCause instanceof FabricatedErrorToKillTheReplayer)) {
                    throw e.immediateCause;
                }
            } finally {
                waitForWorkerThreadsToStop();
                log.info("Upon appending.... counter="+counter.get()+" totalUnique="+totalUniqueEverReceived.get()+
                        " runNumber="+runNumber + "\n" +
                        completelyHandledItems.keySet().stream().sorted().collect(Collectors.joining("\n")));
                log.info(Strings.repeat("\n", 20));
                receivedPerRun.add(counter.get());
                totalUniqueEverReceivedSizeAfterEachRun.add(totalUniqueEverReceived.get());
            }
        }
        log.atInfo().setMessage(()->"completely received request keys=\n{}")
                .addArgument(completelyHandledItems.keySet().stream().sorted().collect(Collectors.joining("\n")))
                .log();
        var skippedPerRun = IntStream.range(0, receivedPerRun.size())
                .map(i->totalUniqueEverReceivedSizeAfterEachRun.get(i)-receivedPerRun.get(i)).toArray();
        log.atInfo().setMessage(()->"Summary: (run #, uniqueSoFar, receivedThisRun, skipped)\n" +
                        IntStream.range(0, receivedPerRun.size()).mapToObj(i->
                                        new StringJoiner(", ")
                                                .add(""+i)
                                                .add(""+totalUniqueEverReceivedSizeAfterEachRun.get(i))
                                                .add(""+receivedPerRun.get(i))
                                                .add(""+skippedPerRun[i]).toString())
                                .collect(Collectors.joining("\n")))
                .log();
        var skippedPerRunDiffs = IntStream.range(0, receivedPerRun.size()-1)
                .map(i->(skippedPerRun[i]<=skippedPerRun[i+1]) ? 1 : 0)
                .toArray();
        var expectedSkipArray = new int[skippedPerRunDiffs.length];
        Arrays.fill(expectedSkipArray, 1);
        Assertions.assertArrayEquals(expectedSkipArray, skippedPerRunDiffs);
        Assertions.assertEquals(numExpectedRequests, totalUniqueEverReceived.get());
    }

    private static void runTrafficReplayer(Supplier<ISimpleTrafficCaptureSource> captureSourceSupplier,
                                           URI endpoint,
                                           Consumer<SourceTargetCaptureTuple> tupleReceiver) throws Exception {
        log.info("Starting a new replayer and running it");
        var tr = new TrafficReplayer(endpoint,
                new StaticAuthTransformerFactory("TEST"),
                true, 10, 10*1024,
                TrafficReplayer.buildDefaultJsonTransformer(endpoint.getHost()));

        try (var os = new NullOutputStream();
             var trafficSource = captureSourceSupplier.get();
             var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2))) {
            tr.setupRunAndWaitForReplayWithShutdownChecks(Duration.ofSeconds(70), blockingTrafficSource,
                    new TimeShifter(10 * 1000), tupleReceiver);
        }
    }

    private static void waitForWorkerThreadsToStop() throws InterruptedException {
        var sleepMs = 2;
        final var MAX_SLEEP_MS = 100;
        while (true) {
            var rootThreadGroup = getRootThreadGroup();
            if (!foundClientPoolThread(rootThreadGroup)) {
                log.info("No client connection pool threads, done polling.");
                return;
            } else {
                log.trace("Found a client connection pool - waiting briefly and retrying.");
                Thread.sleep(sleepMs);
                sleepMs = Math.max(MAX_SLEEP_MS, sleepMs*2);
            }
        }
    }

    private static boolean foundClientPoolThread(ThreadGroup group) {
        Thread[] threads = new Thread[group.activeCount()*2];
        var numThreads = group.enumerate(threads);
        for (int i=0; i<numThreads; ++i) {
            if (threads[i].getName().startsWith(ClientConnectionPool.TARGET_CONNECTION_POOL_NAME)) {
                return true;
            }
        }

        int numGroups = group.activeGroupCount();
        ThreadGroup[] groups = new ThreadGroup[numGroups * 2];
        numGroups = group.enumerate(groups, false);
        for (int i=0; i<numGroups; ++i) {
            if (foundClientPoolThread(groups[i])) {
                return true;
            }
        }
        return false;
    }

    private static ThreadGroup getRootThreadGroup() {
        var rootThreadGroup = Thread.currentThread().getThreadGroup();
        while (true) {
            var tmp = rootThreadGroup.getParent();
            if (tmp != null) { rootThreadGroup = tmp; }
            else { return rootThreadGroup; }
        }
    }

}
