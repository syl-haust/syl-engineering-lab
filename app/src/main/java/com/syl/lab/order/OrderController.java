package com.syl.lab.order;

import com.syl.lab.auth.AuthInterceptor;
import com.syl.lab.idempotent.DuplicateRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 订单接口（全部需要登录，由 AuthInterceptor 校验 access token）。
 *
 * <p>演示要点：</p>
 * <ul>
 *   <li>{@code POST /api/order/submit} 带 {@code Idempotency-Key} 头，
 *       同一个 key 重复调用只会创建一张订单，第二次直接回放第一次的结果。</li>
 *   <li>创建后 30 分钟不支付，会被 RabbitMQ 延时队列自动取消。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public record SubmitRequest(long skuId, BigDecimal amount) {
    }

    @PostMapping("/submit")
    public Map<String, Object> submit(
            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) long userId,
            @RequestBody SubmitRequest req) {
        return orderService.submit(new OrderService.SubmitCommand(
                userId, req.skuId(), req.amount() == null ? new BigDecimal("9.90") : req.amount()));
    }

    @GetMapping("/my")
    public List<Map<String, Object>> myOrders(
            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) long userId) {
        return orderService.findByUser(userId);
    }

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<Map<String, Object>> onDuplicate(DuplicateRequestException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "DUPLICATE_REQUEST", "message", e.getMessage()));
    }
}