package com.platform.searchservice.event;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 컨테이너 기동 회귀 방어 — "스프링이 이 빈을 만들 수 있는가".
 *
 * 이 클래스는 생성자가 둘(운영용 public, 테스트용 package-private)인데 어느 쪽에도 표시가 없어,
 * 스프링이 고를 수 없어서 기본 생성자로 폴백했고 그런 게 없어 컨텍스트가 통째로 죽었다.
 * 실 JAR/컨테이너에서만 터졌던 이유는 기존 컨텍스트 테스트가 {@code platform.events.enabled=false}라
 * {@code @ConditionalOnProperty}가 이 빈을 아예 등록하지 않았기 때문이다 — 정확히 이 자리가 빈 채로 남아 있었다.
 *
 * 그래서 리플렉션으로 생성자 개수를 세거나 직접 {@code new} 하는 검사로는 이 결함을 못 잡는다.
 * 반드시 **스프링에게 만들라고 시켜야** 한다. 아래는 그걸 하되,
 *
 * <ul>
 *   <li>{@code ApplicationContextRunner}는 {@code SpringApplication.run()}이 아니라 평범한
 *       {@code AnnotationConfigApplicationContext}를 띄운다 → {@code ApplicationReadyEvent}가
 *       발행되지 않으므로 {@link RedisStreamEventConsumer#onApplicationReady()}가 돌지 않는다.
 *       소비 루프도 스트림 지원 확인도 시작되지 않는다.</li>
 *   <li>협력자는 목이라 Redis·OpenSearch·gRPC에 실제로 연결하지 않는다.</li>
 * </ul>
 */
class RedisStreamEventConsumerBeanCreationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ConsumerUnderTest.class);

    /**
     * 결함 재현 지점. 생성자 선택이 안 되면 여기서
     * {@code BeanCreationException: No default constructor found}로 컨텍스트가 실패한다.
     */
    @Test
    void springInstantiatesConsumerWhenEventsEnabled() {
        runner.withPropertyValues("platform.events.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RedisStreamEventConsumer.class);

            // 컨텍스트가 뜨는 것만으로 소비가 시작되면 안 된다 — 기동과 소비는 분리돼 있고,
            // 소비는 ApplicationReadyEvent가 왔을 때만 시작한다.
            //
            // redis 목에 대해 verifyNoInteractions를 쓰면 안 된다: 목도 스프링 빈이라
            // StringRedisTemplate이 구현한 InitializingBean·Aware 콜백이 목에 기록된다(실측).
            // 소비 경로가 실제로 타는 지점만 본다 — onApplicationReady()가 돌았다면
            // 제일 먼저 verifyStreamSupport()가 execute(RedisCallback)을 부른다.
            verify(context.getBean(StringRedisTemplate.class), never()).execute(any(RedisCallback.class));
            verifyNoInteractions(context.getBean(PlatformEventIndexer.class));
        });
    }

    /** 스위치가 반대로 동작하는지도 함께 고정한다 — 끄면 빈 자체가 없어야 한다. */
    @Test
    void consumerIsNotRegisteredWhenEventsDisabled() {
        runner.withPropertyValues("platform.events.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RedisStreamEventConsumer.class);
        });
    }

    /**
     * 운영과 같은 방식으로 등록한다 — 생성자 인자를 명시하지 않고 클래스만 준다.
     * 그래야 스프링이 운영에서 하는 것과 똑같은 생성자 선택을 수행한다.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(RedisStreamEventConsumer.class)
    static class ConsumerUnderTest {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        PlatformEventIndexer platformEventIndexer() {
            return mock(PlatformEventIndexer.class);
        }

        @Bean
        EventConsumerProperties eventConsumerProperties() {
            return new EventConsumerProperties();
        }
    }
}
