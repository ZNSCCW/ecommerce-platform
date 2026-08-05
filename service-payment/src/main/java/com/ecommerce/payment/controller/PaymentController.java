package com.ecommerce.payment.controller;

import com.ecommerce.common.Result;
import com.ecommerce.payment.dto.PayRequest;
import com.ecommerce.payment.dto.PayResponse;
import com.ecommerce.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 创建支付
     */
    @PostMapping
    public Result<PayResponse> createPay(@RequestAttribute("userId") Long userId,
                                          @Valid @RequestBody PayRequest request) {
        return Result.success(paymentService.createPay(request, userId));
    }

    /**
     * 支付宝异步回调通知接口（支付宝 POST 调用）
     * 注意：此接口是支付宝回调，不需要鉴权
     */
    @PostMapping("/notify")
    public String notify(HttpServletRequest request) {
        Map<String, String> params = getParamsFromRequest(request);
        return paymentService.handleNotify(params);
    }

    /**
     * 从 HttpServletRequest 提取参数
     */
    private Map<String, String> getParamsFromRequest(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        Map<String, String[]> requestParams = request.getParameterMap();
        for (Map.Entry<String, String[]> entry : requestParams.entrySet()) {
            params.put(entry.getKey(), entry.getValue()[0]);
        }
        return params;
    }
}
