/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.query.web;

import java.io.IOException;

import java.util.concurrent.ThreadFactory;

import com.addthis.codec.config.Configs;
import com.addthis.hydra.query.MeshQueryMaster;
import com.addthis.hydra.query.loadbalance.NextQueryTask;
import com.addthis.hydra.query.loadbalance.QueryQueue;
import com.addthis.hydra.query.tracker.QueryTracker;
import com.addthis.hydra.common.util.CloseTask;

import com.google.common.annotations.VisibleForTesting;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import static java.lang.System.out;

public class QueryServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(QueryServer.class);

    private final QueryQueue queryQueue;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final EventExecutorGroup executorGroup;
    private final ServerBootstrap serverBootstrap;

    public static void main(String[] args) throws IOException, InterruptedException {
        if ((args.length > 0) && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.println("usage: qmaster");
        }
        QueryServer queryServer = Configs.newDefault(QueryServer.class);
        queryServer.run();
        Runtime.getRuntime().addShutdownHook(new Thread(new CloseTask(queryServer), "Query Master Shutdown Hook"));
    }

    @JsonCreator
    private QueryServer(@JsonProperty(value = "webPort", required = true) int webPort,
                        @JsonProperty(value = "queryThreads", required = true) int queryThreads,
                        @JsonProperty(value = "queryThreadFactory", required = true) ThreadFactory queryThreadFactory
    ) throws Exception {
        queryQueue = new QueryQueue();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        executorGroup = new DefaultEventExecutorGroup(queryThreads, queryThreadFactory);

        QueryTracker queryTracker = new QueryTracker();
        MeshQueryMaster meshQueryMaster = new MeshQueryMaster(queryTracker);
        HttpQueryHandler httpQueryHandler = new HttpQueryHandler(queryTracker, meshQueryMaster, queryQueue);
        ChannelHandler queryServerInitializer = new QueryServerInitializer(httpQueryHandler);

        serverBootstrap = new ServerBootstrap()
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.MESSAGE_SIZE_ESTIMATOR, new DefaultMessageSizeEstimator(200))
                .childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 100000000)
                .childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 50000000)
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(queryServerInitializer)
                .localAddress(webPort);
        log.info("[init] web port={}, query threads={}", webPort, queryThreads);
    }

    @VisibleForTesting
    void run() throws InterruptedException {
        for (EventExecutor executor : executorGroup) {
            executor.execute(new NextQueryTask(queryQueue, executor));
        }
        serverBootstrap.bind().sync();
    }

    @Override public void close() throws InterruptedException {
        log.info("shutting down boss group");
        bossGroup.shutdownGracefully().sync();
        log.info("shutting down worker group");
        workerGroup.shutdownGracefully().sync();
        // no sync because there is apparently no easy way to interrupt the take() calls?
        log.info("shutting down query group");
        executorGroup.shutdownGracefully();
        log.info("query server shutdown complete");
    }
}
