package com.example.adaptt.server;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.config.DatabasePool;
import com.example.adaptt.metrics.LatencyWindow;
import com.example.adaptt.metrics.CsvMetricsReporter;
import com.example.adaptt.metrics.WindowCoordinator;
import com.example.adaptt.workload.DbReadWorkload;
import com.example.adaptt.workload.DbWriteWorkload;
import com.example.adaptt.workload.ExperimentApplication;
import com.example.adaptt.workload.PrimeWorkload;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AdaptTServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdaptTServer.class);
    private static final int MAX_HTTP_CONTENT_LENGTH = 8 * 1024;

    private AdaptTServer() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        ExperimentApplication application = ExperimentApplication.parse(
                config.getString("experiment.application"));
        PrimeWorkload primeWorkload = PrimeWorkload.from(config);
        DatabasePool databasePool = application != ExperimentApplication.PRIME
                ? DatabasePool.from(config)
                : null;
        if (databasePool != null) {
            databasePool.verify();
        }
        DbReadWorkload dbReadWorkload = databasePool == null
                || application != ExperimentApplication.DBREAD
                ? null
                : DbReadWorkload.from(config, databasePool);
        DbWriteWorkload dbWriteWorkload = databasePool == null
                || application != ExperimentApplication.DBWRITE
                ? null
                : DbWriteWorkload.from(config, databasePool);
        AdjustableWorkerPool requestWorkers = AdjustableWorkerPool.from(config);
        LatencyWindow latencyWindow = LatencyWindow.from(config);
        ObjectMapper objectMapper = new ObjectMapper();

        EventLoopGroup bossGroup = new NioEventLoopGroup(
                config.getInt("netty.bossThreads"));
        EventLoopGroup ioGroup = new NioEventLoopGroup(
                config.getInt("netty.ioThreads"));
        CsvMetricsReporter metricsReporter = CsvMetricsReporter.from(config);
        WindowCoordinator windowCoordinator = WindowCoordinator.from(
                config, latencyWindow, requestWorkers, metricsReporter);
        ExperimentHttpHandler requestHandler = new ExperimentHttpHandler(
                requestWorkers,
                application,
                primeWorkload,
                dbReadWorkload,
                dbWriteWorkload,
                databasePool,
                latencyWindow,
                windowCoordinator,
                objectMapper);

        Runtime.getRuntime().addShutdownHook(new Thread(
                requestWorkers::shutdown,
                "request-worker-shutdown"));

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, ioGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG,
                            config.getInt("server.backlog"))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new io.netty.channel.ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast("http-codec", new HttpServerCodec())
                                    .addLast("http-aggregator",
                                            new HttpObjectAggregator(
                                                    MAX_HTTP_CONTENT_LENGTH))
                                    .addLast("request-handler", requestHandler);
                        }
                    });

            String host = config.getString("server.host");
            int port = config.getInt("server.port");
            Channel serverChannel = bootstrap.bind(host, port).sync().channel();
            LOGGER.info(
                    "ADAPT-T server listening on {}:{} application={} with {} request workers",
                    host,
                    port,
                    application,
                    requestWorkers.configuredSize());
            serverChannel.closeFuture().sync();
        } finally {
            windowCoordinator.close();
            requestWorkers.close();
            if (databasePool != null) {
                databasePool.close();
            }
            ioGroup.shutdownGracefully().sync();
            bossGroup.shutdownGracefully().sync();
        }
    }
}
