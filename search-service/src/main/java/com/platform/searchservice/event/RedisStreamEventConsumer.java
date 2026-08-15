package com.platform.searchservice.event;

import com.google.protobuf.InvalidProtocolBufferException;
import com.platform.proto.events.v1.EventEnvelope;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands.XPendingOptions;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * wiki 이벤트 스트림의 at-least-once 소비 루프.
 *
 * 메시지는 색인 처리 뒤에만 ACK한다. 실패 횟수는 별도 메모리가 아니라 Redis PEL의 delivery
 * count로 센다. 그래야 프로세스가 재기동돼도 횟수가 사라지지 않고 영구 장애 이벤트가 무한
 * 재시도에 굳지 않는다.
 */
@Component
@Slf4j
@ConditionalOnProperty(value = "platform.events.enabled", havingValue = "true", matchIfMissing = true)
public class RedisStreamEventConsumer {

    private static final byte[] PAYLOAD_FIELD = "payload".getBytes(StandardCharsets.UTF_8);
    private static final int BATCH_SIZE = 10;
    private static final int PENDING_SCAN_SIZE = 100;
    private static final Duration DEFAULT_RETRY_IDLE = Duration.ofSeconds(30);
    private static final Duration DEFAULT_READ_BLOCK = Duration.ofSeconds(2);
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);
    private static final byte[] DLQ_AND_ACK_SCRIPT = """
            redis.call('XADD', KEYS[1], '*',
              'payload', ARGV[1],
              'original_stream', KEYS[2],
              'original_id', ARGV[2],
              'consumer_group', ARGV[3],
              'delivery_count', ARGV[4],
              'failed_at', ARGV[5],
              'error', ARGV[6])
            return redis.call('XACK', KEYS[2], ARGV[3], ARGV[2])
            """.getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redis;
    private final PlatformEventIndexer indexer;
    private final String stream;
    private final byte[] streamKey;
    private final String consumerGroup;
    private final String consumerName;
    private final int maxRetries;
    private final String dlq;
    private final byte[] dlqKey;
    private final Duration retryIdle;
    private final Duration readBlock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor;

    /**
     * 운영 주입 생성자. 아래 package-private 생성자(테스트가 타이밍을 줄여 쓰는 용도)와 둘이라
     * {@code @Autowired}가 없으면 스프링이 어느 쪽을 쓸지 못 정하고 기본 생성자로 폴백하다가
     * "No default constructor found"로 컨텍스트가 통째로 죽는다. 이 표시가 그 선택을 고정한다.
     */
    @Autowired
    public RedisStreamEventConsumer(
            StringRedisTemplate redis,
            PlatformEventIndexer indexer,
            EventConsumerProperties properties,
            @Value("${spring.application.name:search-service}") String applicationName) {
        this(redis, indexer, properties, applicationName, DEFAULT_RETRY_IDLE, DEFAULT_READ_BLOCK);
    }

    /** 테스트 전용 — 재시도/블록 타이밍을 줄여 주입한다. 스프링은 이 생성자를 쓰지 않는다. */
    RedisStreamEventConsumer(
            StringRedisTemplate redis,
            PlatformEventIndexer indexer,
            EventConsumerProperties properties,
            String applicationName,
            Duration retryIdle,
            Duration readBlock) {
        Assert.hasText(properties.getStream(), "platform.events.stream은 비어 있을 수 없습니다");
        Assert.hasText(properties.getConsumerGroup(), "platform.events.consumer-group은 비어 있을 수 없습니다");
        Assert.hasText(properties.getDlq(), "platform.events.dlq는 비어 있을 수 없습니다");
        Assert.isTrue(properties.getMaxRetries() >= 0, "platform.events.max-retries는 0 이상이어야 합니다");
        Assert.isTrue(!retryIdle.isNegative() && !retryIdle.isZero(), "retryIdle은 양수여야 합니다");
        Assert.isTrue(!readBlock.isNegative() && !readBlock.isZero(), "readBlock은 양수여야 합니다");

        this.redis = redis;
        this.indexer = indexer;
        this.stream = properties.getStream();
        this.streamKey = stream.getBytes(StandardCharsets.UTF_8);
        this.consumerGroup = properties.getConsumerGroup();
        this.maxRetries = properties.getMaxRetries();
        this.dlq = properties.getDlq();
        this.dlqKey = dlq.getBytes(StandardCharsets.UTF_8);
        this.retryIdle = retryIdle;
        this.readBlock = readBlock;
        this.consumerName = applicationName + "-" + UUID.randomUUID();
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "redis-stream-" + consumerName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Wave C의 wiki 전용 소비자 테스트와 소스 호환. */
    RedisStreamEventConsumer(
            StringRedisTemplate redis,
            WikiEventIndexer indexer,
            EventConsumerProperties properties,
            String applicationName,
            Duration retryIdle,
            Duration readBlock) {
        this(redis, new PlatformEventIndexer(indexer, null), properties, applicationName, retryIdle, readBlock);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        verifyStreamSupport();
        start();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::consumeLoop);
        }
    }

    /**
     * 발행자와 같은 기동 진단이다. 스트림 미지원 Redis여도 서비스 부팅 자체는 막지 않지만,
     * 검색 색인이 갱신되지 않는 상태를 ERROR로 분명하게 드러낸다.
     */
    public void verifyStreamSupport() {
        try {
            Properties info = redis.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info("server"));
            String version = info == null ? null : info.getProperty("redis_version");
            if (version == null) {
                log.warn("Redis 버전을 확인하지 못했습니다 — 스트림 지원 여부 미확인");
                return;
            }
            int major = Integer.parseInt(version.split("\\.")[0]);
            if (major < 5) {
                log.error("연결된 Redis {}는 스트림(XREADGROUP)을 지원하지 않습니다(5.0+ 필요) — "
                        + "검색 색인이 갱신되지 않습니다. 연결 대상을 확인하세요.", version);
            } else {
                log.info("이벤트 스트림 소비 Redis {} — 스트림 지원 확인", version);
            }
        } catch (Exception e) {
            log.warn("Redis 스트림 지원 확인 실패(소비 루프에서 재확인됨)", e);
        }
    }

    private void consumeLoop() {
        boolean groupReady = false;
        while (running.get()) {
            try {
                if (!groupReady) {
                    ensureConsumerGroup();
                    groupReady = true;
                }
                if (!retryPending()) {
                    readNewMessages();
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Redis Streams 소비 루프 실패: stream={} group={} consumer={}",
                            stream, consumerGroup, consumerName, e);
                    groupReady = !containsMessage(e, "NOGROUP");
                    pause(ERROR_BACKOFF);
                }
            }
        }
    }

    private void ensureConsumerGroup() {
        try {
            redis.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            streamKey, consumerGroup, ReadOffset.from("0-0"), true));
            log.info("Redis Streams consumer group 생성: stream={} group={}", stream, consumerGroup);
        } catch (RuntimeException e) {
            if (!containsMessage(e, "BUSYGROUP")) {
                throw e;
            }
        }
    }

    private boolean retryPending() {
        PendingMessages pending = redis.execute((RedisCallback<PendingMessages>) connection ->
                connection.streamCommands().xPending(
                        streamKey,
                        consumerGroup,
                        XPendingOptions.unbounded((long) PENDING_SCAN_SIZE)));
        if (pending == null || pending.isEmpty()) {
            return false;
        }

        boolean retried = false;
        for (PendingMessage message : pending) {
            // XPENDING의 IDLE 필터는 Redis 6.2+라 쓰지 않는다. 지원 규약인 Redis 5에서도
            // 동작하도록 응답의 idle 값을 검사하고 XCLAIM의 min-idle-time으로 경합을 막는다.
            if (message.getElapsedTimeSinceLastDelivery().compareTo(retryIdle) < 0) {
                continue;
            }
            List<ByteRecord> claimed = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                    connection.streamCommands().xClaim(
                            streamKey, consumerGroup, consumerName, retryIdle, message.getId()));
            if (claimed == null || claimed.isEmpty()) {
                continue;
            }

            retried = true;
            long deliveryCount = message.getTotalDeliveryCount() + 1;
            for (ByteRecord record : claimed) {
                if (message.getTotalDeliveryCount() > maxRetries) {
                    moveToDlqAndAck(record, deliveryCount,
                            new IllegalStateException("재기동 전에 재시도 상한을 이미 초과한 메시지"));
                } else {
                    handle(record, deliveryCount);
                }
            }
        }
        return retried;
    }

    private void readNewMessages() {
        List<ByteRecord> records = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                connection.streamCommands().xReadGroup(
                        Consumer.from(consumerGroup, consumerName),
                        StreamReadOptions.empty().count(BATCH_SIZE).block(readBlock),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed())));
        if (records == null) {
            return;
        }
        for (ByteRecord record : records) {
            handle(record, 1L);
        }
    }

    private void handle(ByteRecord record, long deliveryCount) {
        try {
            EventEnvelope event = parse(record);
            indexer.handle(event);
            acknowledge(record.getId());
        } catch (Exception e) {
            log.error("색인 이벤트 처리 실패: stream={} id={} deliveryCount={} maxRetries={}",
                    stream, record.getId().getValue(), deliveryCount, maxRetries, e);
            // deliveryCount=1은 최초 시도다. maxRetries=5면 count=2..6까지 다섯 번 재시도한 뒤 DLQ로 보낸다.
            if (deliveryCount > maxRetries) {
                moveToDlqAndAck(record, deliveryCount, e);
            }
        }
    }

    private EventEnvelope parse(ByteRecord record) throws InvalidProtocolBufferException {
        byte[] payload = payload(record);
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload 필드가 없는 스트림 엔트리: id=" + record.getId().getValue());
        }
        return EventEnvelope.parseFrom(payload);
    }

    private void acknowledge(RecordId id) {
        Long acknowledged = redis.execute((RedisCallback<Long>) connection ->
                connection.streamCommands().xAck(streamKey, consumerGroup, id));
        if (acknowledged == null || acknowledged != 1L) {
            throw new IllegalStateException("XACK 실패: stream=" + stream + " id=" + id.getValue());
        }
    }

    private void moveToDlqAndAck(ByteRecord record, long deliveryCount, Exception failure) {
        byte[] payload = payload(record);
        byte[] originalPayload = payload == null ? new byte[0] : payload;
        String raw = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        // DLQ 레코드가 스택트레이스 길이만큼 커지지 않게 자른다. 원인 규명은 ERROR 로그로 한다.
        final String error = raw.length() > 1_000 ? raw.substring(0, 1_000) : raw;

        Long acknowledged = redis.execute((RedisCallback<Long>) (RedisConnection connection) ->
                connection.scriptingCommands().eval(
                        DLQ_AND_ACK_SCRIPT,
                        ReturnType.INTEGER,
                        2,
                        dlqKey,
                        streamKey,
                        originalPayload,
                        bytes(record.getId().getValue()),
                        bytes(consumerGroup),
                        bytes(Long.toString(deliveryCount)),
                        bytes(Instant.now().toString()),
                        bytes(error)));
        if (acknowledged == null || acknowledged != 1L) {
            throw new IllegalStateException(
                    "DLQ 이동/XACK 원자 연산 실패: stream=" + stream + " id=" + record.getId().getValue());
        }
        log.error("색인 이벤트 재시도 상한 초과 — DLQ 이동: stream={} dlq={} id={} deliveryCount={}",
                stream, dlq, record.getId().getValue(), deliveryCount, failure);
    }

    private static byte[] payload(ByteRecord record) {
        for (Map.Entry<byte[], byte[]> field : record.getValue().entrySet()) {
            if (Arrays.equals(PAYLOAD_FIELD, field.getKey())) {
                return field.getValue();
            }
        }
        return null;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean containsMessage(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }
}
