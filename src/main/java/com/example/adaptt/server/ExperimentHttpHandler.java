package com.example.adaptt.server;

import com.example.adaptt.config.DatabasePool;
import com.example.adaptt.metrics.LatencyWindow;
import com.example.adaptt.metrics.MeasurementStatus;
import com.example.adaptt.metrics.WindowCoordinator;
import com.example.adaptt.workload.DbReadWorkload;
import com.example.adaptt.workload.DbWriteWorkload;
import com.example.adaptt.workload.ExperimentApplication;
import com.example.adaptt.workload.PrimeWorkload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

@ChannelHandler.Sharable
final class ExperimentHttpHandler
        extends io.netty.channel.SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String JSON_CONTENT_TYPE = "application/json; charset=UTF-8";

    private final AdjustableWorkerPool workerPool;
    private final ExperimentApplication application;
    private final PrimeWorkload primeWorkload;
    private final DbReadWorkload dbReadWorkload;
    private final DbWriteWorkload dbWriteWorkload;
    private final DatabasePool databasePool;
    private final LatencyWindow latencyWindow;
    private final WindowCoordinator windowCoordinator;
    private final ObjectMapper objectMapper;

    ExperimentHttpHandler(
            AdjustableWorkerPool workerPool,
            ExperimentApplication application,
            PrimeWorkload primeWorkload,
            DbReadWorkload dbReadWorkload,
            DbWriteWorkload dbWriteWorkload,
            DatabasePool databasePool,
            LatencyWindow latencyWindow,
            WindowCoordinator windowCoordinator,
            ObjectMapper objectMapper) {
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.application = Objects.requireNonNull(application, "application");
        this.primeWorkload = primeWorkload;
        this.dbReadWorkload = dbReadWorkload;
        this.dbWriteWorkload = dbWriteWorkload;
        this.databasePool = databasePool;
        this.latencyWindow = Objects.requireNonNull(latencyWindow, "latencyWindow");
        this.windowCoordinator = Objects.requireNonNull(
                windowCoordinator, "windowCoordinator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");

        if (application == ExperimentApplication.PRIME && primeWorkload == null) {
            throw new IllegalArgumentException("PRIME requires PrimeWorkload");
        }
        if (application == ExperimentApplication.DBREAD
                && (dbReadWorkload == null || databasePool == null)) {
            throw new IllegalArgumentException(
                    "DBREAD requires DbReadWorkload and DatabasePool");
        }
        if (application == ExperimentApplication.DBWRITE
                && (dbWriteWorkload == null || databasePool == null)) {
            throw new IllegalArgumentException(
                    "DBWRITE requires DbWriteWorkload and DatabasePool");
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        if ("/health".equals(decoder.path())) {
            if (request.method() != HttpMethod.GET) {
                sendMethodNotAllowed(context, keepAlive, "GET");
                return;
            }
            sendOnEventLoop(context, keepAlive, HttpResponseStatus.OK,
                    jsonMessage("ok"));
        } else if ("/pool".equals(decoder.path())) {
            if (request.method() != HttpMethod.GET) {
                sendMethodNotAllowed(context, keepAlive, "GET");
                return;
            }
            sendPoolStatus(context, keepAlive);
        } else if ("/experiment/start".equals(decoder.path())) {
            if (request.method() != HttpMethod.POST) {
                sendMethodNotAllowed(context, keepAlive, "POST");
                return;
            }
            startMeasurement(context, keepAlive, decoder.parameters());
        } else if ("/experiment/status".equals(decoder.path())) {
            if (request.method() != HttpMethod.GET) {
                sendMethodNotAllowed(context, keepAlive, "GET");
                return;
            }
            sendMeasurementStatus(
                    context, keepAlive, HttpResponseStatus.OK,
                    windowCoordinator.status());
        } else if (application.endpoint().equals(decoder.path())) {
            HttpMethod expectedMethod = application == ExperimentApplication.DBWRITE
                    ? HttpMethod.POST
                    : HttpMethod.GET;
            if (request.method() != expectedMethod) {
                sendMethodNotAllowed(context, keepAlive, expectedMethod.name());
                return;
            }
            submitWorkload(context, keepAlive, decoder.parameters());
        } else {
            sendOnEventLoop(context, keepAlive, HttpResponseStatus.NOT_FOUND,
                    jsonMessage("Active workload endpoint is " + application.endpoint()));
        }
    }

    private void startMeasurement(
            ChannelHandlerContext context,
            boolean keepAlive,
            Map<String, List<String>> parameters) {
        try {
            int windows = parsePositiveInt(parameters, "windows", 150, 10_000);
            int delaySeconds = parsePositiveInt(
                    parameters, "delaySeconds", 5, 60);
            MeasurementStatus status = windowCoordinator.arm(
                    windows, delaySeconds);
            sendMeasurementStatus(
                    context, keepAlive, HttpResponseStatus.ACCEPTED, status);
        } catch (IllegalStateException e) {
            sendOnEventLoop(context, keepAlive, HttpResponseStatus.CONFLICT,
                    jsonMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendOnEventLoop(context, keepAlive, HttpResponseStatus.BAD_REQUEST,
                    jsonMessage(e.getMessage()));
        }
    }

    private int parsePositiveInt(
            Map<String, List<String>> parameters,
            String name,
            int defaultValue,
            int maximum) {
        List<String> values = parameters.get(name);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(values.get(0));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Query parameter " + name + " must be an integer");
        }
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(
                    "Query parameter " + name + " must be between 1 and "
                            + maximum);
        }
        return value;
    }

    private void sendMeasurementStatus(
            ChannelHandlerContext context,
            boolean keepAlive,
            HttpResponseStatus responseStatus,
            MeasurementStatus status) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(status);
            FullHttpResponse response = jsonResponse(responseStatus, body);
            HttpUtil.setKeepAlive(response, keepAlive);
            ChannelFuture write = context.writeAndFlush(response);
            if (!keepAlive) {
                write.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (JsonProcessingException e) {
            sendOnEventLoop(context, keepAlive,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    jsonMessage("Unable to encode measurement status"));
        }
    }

    private void submitWorkload(
            ChannelHandlerContext context,
            boolean keepAlive,
            Map<String, List<String>> parameters) {
        long arrivalNanos = System.nanoTime();
        Long requestedValue;
        try {
            requestedValue = parseRequestedValue(parameters);
        } catch (IllegalArgumentException e) {
            latencyWindow.recordFailure();
            sendOnEventLoop(context, keepAlive, HttpResponseStatus.BAD_REQUEST,
                    jsonMessage(e.getMessage()));
            return;
        }

        try {
            workerPool.execute(() -> executeWorkload(
                    context, keepAlive, requestedValue, arrivalNanos));
        } catch (RejectedExecutionException e) {
            latencyWindow.recordFailure();
            sendOnEventLoop(context, keepAlive, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    jsonMessage("Request worker queue is full"));
        }
    }

    private void executeWorkload(
            ChannelHandlerContext context,
            boolean keepAlive,
            Long requestedValue,
            long arrivalNanos) {
        try {
            Object result = switch (application) {
                case PRIME -> requestedValue == null
                        ? primeWorkload.executeRandom()
                        : primeWorkload.execute(requestedValue);
                case DBREAD -> requestedValue == null
                        ? dbReadWorkload.executeRandom()
                        : dbReadWorkload.execute(requestedValue);
                case DBWRITE -> dbWriteWorkload.execute();
            };

            byte[] body = objectMapper.writeValueAsBytes(result);
            FullHttpResponse response = jsonResponse(HttpResponseStatus.OK, body);
            HttpUtil.setKeepAlive(response, keepAlive);

            context.executor().execute(() -> {
                ChannelFuture write = context.writeAndFlush(response);
                write.addListener(future -> {
                    if (future.isSuccess()) {
                        latencyWindow.recordSuccess(System.nanoTime() - arrivalNanos);
                    } else {
                        latencyWindow.recordFailure();
                    }
                });
                if (!keepAlive) {
                    write.addListener(ChannelFutureListener.CLOSE);
                }
            });
        } catch (NoSuchElementException e) {
            latencyWindow.recordFailure();
            sendFromWorker(context, keepAlive, HttpResponseStatus.NOT_FOUND,
                    e.getMessage());
        } catch (Exception e) {
            latencyWindow.recordFailure();
            sendFromWorker(context, keepAlive,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Workload execution failed");
        }
    }

    private Long parseRequestedValue(Map<String, List<String>> parameters) {
        if (application == ExperimentApplication.DBWRITE) {
            return null;
        }
        String name = application == ExperimentApplication.PRIME ? "n" : "id";
        List<String> values = parameters.get(name);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            return null;
        }

        long value;
        try {
            value = Long.parseLong(values.get(0));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Query parameter " + name + " must be an integer");
        }

        if (application == ExperimentApplication.PRIME
                && (value < primeWorkload.lowerBound()
                || value > primeWorkload.upperBound())) {
            throw new IllegalArgumentException(
                    "Query parameter n is outside the configured Prime range");
        }
        if (application == ExperimentApplication.DBREAD
                && (value < dbReadWorkload.minimumId()
                || value > dbReadWorkload.maximumId())) {
            throw new IllegalArgumentException(
                    "Query parameter id is outside the configured DBRead range");
        }
        return value;
    }

    private void sendPoolStatus(ChannelHandlerContext context, boolean keepAlive) {
        StringBuilder body = new StringBuilder(256)
                .append("{\"application\":\"").append(application).append("\"")
                .append(",\"configuredSize\":").append(workerPool.configuredSize())
                .append(",\"liveThreads\":").append(workerPool.liveThreadCount())
                .append(",\"activeThreads\":").append(workerPool.activeCount())
                .append(",\"queueSize\":").append(workerPool.queueSize())
                .append(",\"completedTasks\":").append(workerPool.completedTaskCount())
                .append(",\"rejectedTasks\":").append(workerPool.rejectedTaskCount());
        if (databasePool != null) {
            body.append(",\"dbTotalConnections\":")
                    .append(databasePool.totalConnections())
                    .append(",\"dbActiveConnections\":")
                    .append(databasePool.activeConnections())
                    .append(",\"dbIdleConnections\":")
                    .append(databasePool.idleConnections())
                    .append(",\"dbThreadsAwaitingConnection\":")
                    .append(databasePool.threadsAwaitingConnection());
        }
        body.append('}');
        sendOnEventLoop(context, keepAlive, HttpResponseStatus.OK, body.toString());
    }

    private void sendFromWorker(
            ChannelHandlerContext context,
            boolean keepAlive,
            HttpResponseStatus status,
            String message) {
        context.executor().execute(() -> sendOnEventLoop(
                context, keepAlive, status, jsonMessage(message)));
    }

    private void sendMethodNotAllowed(
            ChannelHandlerContext context,
            boolean keepAlive,
            String expectedMethod) {
        sendOnEventLoop(
                context,
                keepAlive,
                HttpResponseStatus.METHOD_NOT_ALLOWED,
                jsonMessage("Expected HTTP method " + expectedMethod));
    }

    private static void sendOnEventLoop(
            ChannelHandlerContext context,
            boolean keepAlive,
            HttpResponseStatus status,
            String body) {
        FullHttpResponse response = jsonResponse(
                status, body.getBytes(StandardCharsets.UTF_8));
        HttpUtil.setKeepAlive(response, keepAlive);
        ChannelFuture write = context.writeAndFlush(response);
        if (!keepAlive) {
            write.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static FullHttpResponse jsonResponse(
            HttpResponseStatus status,
            byte[] body) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, JSON_CONTENT_TYPE);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return response;
    }

    private String jsonMessage(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("message", message));
        } catch (JsonProcessingException e) {
            return "{\"message\":\"response encoding failed\"}";
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        latencyWindow.recordFailure();
        context.close();
    }
}
