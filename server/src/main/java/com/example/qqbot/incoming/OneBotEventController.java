package com.example.qqbot.incoming;

import com.example.qqbot.guard.MessageDeduplicator;
import com.example.qqbot.onebot.model.OneBotEvent;
import com.example.qqbot.router.MessageRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件入口：NapCat 的「网络配置 → HTTP客户端（HTTP上报）」指向这里。
 *
 * <p>完整地址是 http://host.docker.internal:8080/onebot/event
 *
 * <p>注意：这里必须「先返回 204，再异步处理」。因为接入大模型之后一次处理要好几秒，
 * 而 NapCat 会一直等这个响应。
 */
@RestController
@RequestMapping("/onebot")
public class OneBotEventController {

    private static final Logger log = LoggerFactory.getLogger(OneBotEventController.class);

    private final MessageRouter router;
    private final MessageDeduplicator deduplicator;

    public OneBotEventController(MessageRouter router, MessageDeduplicator deduplicator) {
        this.router = router;
        this.deduplicator = deduplicator;
    }

    @PostMapping("/event")
    public ResponseEntity<Void> onEvent(@RequestBody OneBotEvent event) {
        if (deduplicator.isDuplicate(event)) {
            log.debug("重复事件，已忽略：{}", event);
            return ResponseEntity.noContent().build();
        }
        // 把「提交前」的时间戳传进去，让 worker 能判断自己在队列里排了多久
        router.handleAsync(event, System.currentTimeMillis());
        // 204：告诉 NapCat「收到了」
        return ResponseEntity.noContent().build();
    }
}
