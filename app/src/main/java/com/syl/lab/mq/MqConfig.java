package com.syl.lab.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑定义。
 *
 * <h3>为什么要"延时队列"而不是定时扫表</h3>
 * <p>订单 30 分钟未支付要自动取消。最朴素的实现是一个定时任务每分钟扫一遍
 * {@code where status=0 and create_time < now-30min}。数据量一大，
 * 扫描条件用不上索引、每次全表扫、还会和业务查询抢资源。</p>
 *
 * <p>RabbitMQ 的延时队列把"到点了该干活"变成一次投递：
 * 下单时投一条 TTL=30 分钟的消息到 {@link #DELAY_EXCHANGE}，
 * 消息过期后由死信机制自动路由到 {@link #EXPIRE_QUEUE}。</p>
 *
 * <p><b>一个容易踩的坑</b>：延时队列必须用「每条消息单独 TTL + 死信」的方式，
 * 不能用「队列级 x-message-ttl」。因为队列级 TTL 是"队头阻塞"的：
 * 如果先投了 30 分钟的消息、再投 5 分钟的消息，5 分钟那条会排在后面，
 * 要等前面的出队才会过期。所以这里用 {@code x-message-ttl} 只做演示，
 * 生产环境推荐用 {@code rabbitmq-delayed-message-exchange} 插件，
 * 或者每个延时档位一个队列（本项目用后者：见 {@link #DELAY_30M_QUEUE}）。</p>
 */
@Configuration
public class MqConfig {

    public static final String ORDER_EXCHANGE = "lab.order.exchange";
    public static final String ORDER_PAID_QUEUE = "lab.order.paid.queue";
    public static final String ORDER_PAID_KEY = "order.paid";

    public static final String DELAY_EXCHANGE = "lab.order.delay.exchange";
    public static final String DELAY_30M_QUEUE = "lab.order.delay.30m.queue";
    public static final String DELAY_KEY = "order.delay.30m";

    public static final String EXPIRE_EXCHANGE = "lab.order.expire.exchange";
    public static final String EXPIRE_QUEUE = "lab.order.expire.queue";
    public static final String EXPIRE_KEY = "order.expire";

    public static final String DLX = "lab.order.dlx.exchange";
    public static final String DEAD_QUEUE = "lab.order.dead.queue";
    public static final String DEAD_KEY = "order.dead";

    /** 订单主交换机 */
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    /**
     * JSON 消息转换器。
     *
     * <p>Spring AMQP 默认用 {@code SimpleMessageConverter}，只支持
     * String / byte[] / Serializable。我们的 {@link OrderMessage} 是 record，
     * 既不实现 Serializable 也不能被它识别，直接发会抛
     * "only supports String, byte[] and Serializable payloads"。</p>
     *
     * <p>换成 Jackson2JsonMessageConverter 有两个好处：
     * 消息体是可读的 JSON（在管理台里能直接看内容），且跨语言可消费。</p>
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    @Bean
    public Queue orderPaidQueue() {
        return QueueBuilder.durable(ORDER_PAID_QUEUE).build();
    }

    @Bean
    public Binding orderPaidBinding() {
        return BindingBuilder.bind(orderPaidQueue()).to(orderExchange()).with(ORDER_PAID_KEY);
    }

    /** 延时交换机：下单后投递，30 分钟后进死信 */
    @Bean
    public DirectExchange delayExchange() {
        return new DirectExchange(DELAY_EXCHANGE, true, false);
    }

    /**
     * 30 分钟延时队列。
     *
     * <p>x-dead-letter-exchange 指向 EXPIRE_EXCHANGE：
     * 消息过期后不会丢弃，而是被路由到过期处理队列。</p>
     */
    @Bean
    public Queue delay30mQueue() {
        return QueueBuilder.durable(DELAY_30M_QUEUE)
                .withArgument("x-dead-letter-exchange", EXPIRE_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", EXPIRE_KEY)
                .withArgument("x-message-ttl", 30 * 60 * 1000)
                .build();
    }

    @Bean
    public Binding delay30mBinding() {
        return BindingBuilder.bind(delay30mQueue()).to(delayExchange()).with(DELAY_KEY);
    }

    /** 过期处理交换机 + 队列：真正执行"自动取消订单" */
    @Bean
    public DirectExchange expireExchange() {
        return new DirectExchange(EXPIRE_EXCHANGE, true, false);
    }

    @Bean
    public Queue expireQueue() {
        return QueueBuilder.durable(EXPIRE_QUEUE)
                // 消费失败超过重试次数后进死信，人工介入
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DEAD_KEY)
                .build();
    }

    @Bean
    public Binding expireBinding() {
        return BindingBuilder.bind(expireQueue()).to(expireExchange()).with(EXPIRE_KEY);
    }

    /** 死信交换机 + 队列：重试耗尽或消费失败的消息在这里，不会被静默丢弃 */
    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue()).to(dlxExchange()).with(DEAD_KEY);
    }
}