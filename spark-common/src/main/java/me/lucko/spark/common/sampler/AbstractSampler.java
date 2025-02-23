/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.sampler;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.sender.CommandSender;
import me.lucko.spark.common.monitor.memory.GarbageCollectorStatistics;
import me.lucko.spark.common.platform.serverconfig.ServerConfigProvider;
import me.lucko.spark.common.sampler.aggregator.DataAggregator;
import me.lucko.spark.common.sampler.node.MergeMode;
import me.lucko.spark.common.sampler.node.ThreadNode;
import me.lucko.spark.common.util.ClassSourceLookup;
import me.lucko.spark.proto.SparkSamplerProtos.SamplerData;
import me.lucko.spark.proto.SparkSamplerProtos.SamplerMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base implementation class for {@link Sampler}s.
 */
public abstract class AbstractSampler implements Sampler {

    /** The interval to wait between sampling, in microseconds */
    protected final int interval;

    /** The instance used to generate thread information for use in sampling */
    protected final ThreadDumper threadDumper;

    /** The time when sampling first began */
    protected long startTime = -1;

    /** The unix timestamp (in millis) when this sampler should automatically complete. */
    protected final long endTime; // -1 for nothing

    /** A future to encapsulate the completion of this sampler instance */
    protected final CompletableFuture<Sampler> future = new CompletableFuture<>();

    /** The garbage collector statistics when profiling started */
    protected Map<String, GarbageCollectorStatistics> initialGcStats;

    protected AbstractSampler(int interval, ThreadDumper threadDumper, long endTime) {
        this.interval = interval;
        this.threadDumper = threadDumper;
        this.endTime = endTime;
    }

    @Override
    public long getStartTime() {
        if (this.startTime == -1) {
            throw new IllegalStateException("Not yet started");
        }
        return this.startTime;
    }

    @Override
    public long getEndTime() {
        return this.endTime;
    }

    @Override
    public CompletableFuture<Sampler> getFuture() {
        return this.future;
    }

    protected void recordInitialGcStats() {
        this.initialGcStats = GarbageCollectorStatistics.pollStats();
    }

    protected Map<String, GarbageCollectorStatistics> getInitialGcStats() {
        return this.initialGcStats;
    }

    protected void writeMetadataToProto(SamplerData.Builder proto, SparkPlatform platform, CommandSender creator, String comment, DataAggregator dataAggregator) {
        SamplerMetadata.Builder metadata = SamplerMetadata.newBuilder()
                .setPlatformMetadata(platform.getPlugin().getPlatformInfo().toData().toProto())
                .setCreator(creator.toData().toProto())
                .setStartTime(this.startTime)
                .setInterval(this.interval)
                .setThreadDumper(this.threadDumper.getMetadata())
                .setDataAggregator(dataAggregator.getMetadata());

        if (comment != null) {
            metadata.setComment(comment);
        }

        try {
            metadata.setPlatformStatistics(platform.getStatisticsProvider().getPlatformStatistics(getInitialGcStats()));
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            metadata.setSystemStatistics(platform.getStatisticsProvider().getSystemStatistics());
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            ServerConfigProvider serverConfigProvider = platform.getPlugin().createServerConfigProvider();
            metadata.putAllServerConfigurations(serverConfigProvider.exportServerConfigurations());
        } catch (Exception e) {
            e.printStackTrace();
        }

        proto.setMetadata(metadata);
    }

    protected void writeDataToProto(SamplerData.Builder proto, DataAggregator dataAggregator, Comparator<? super Map.Entry<String, ThreadNode>> outputOrder, MergeMode mergeMode, ClassSourceLookup classSourceLookup) {
        List<Map.Entry<String, ThreadNode>> data = new ArrayList<>(dataAggregator.getData().entrySet());
        data.sort(outputOrder);

        ClassSourceLookup.Visitor classSourceVisitor = ClassSourceLookup.createVisitor(classSourceLookup);

        for (Map.Entry<String, ThreadNode> entry : data) {
            proto.addThreads(entry.getValue().toProto(mergeMode));
            classSourceVisitor.visit(entry.getValue());
        }

        if (classSourceVisitor.hasMappings()) {
            proto.putAllClassSources(classSourceVisitor.getMapping());
        }
    }
}
