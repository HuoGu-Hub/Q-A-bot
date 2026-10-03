package com.example.qqbot;

import com.example.qqbot.config.AdminProperties;
import com.example.qqbot.config.BotProperties;
import com.example.qqbot.config.CommandProperties;
import com.example.qqbot.config.AsyncProperties;
import com.example.qqbot.config.GuardProperties;
import com.example.qqbot.config.KbProperties;
import com.example.qqbot.config.LlmProperties;
import com.example.qqbot.config.LogProperties;
import com.example.qqbot.config.MediaProperties;
import com.example.qqbot.config.OneBotProperties;
import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.config.PlazaProperties;
import com.example.qqbot.config.QaProperties;
import com.example.qqbot.config.SiteProperties;
import com.example.qqbot.onebot.BotIdentity;
import com.example.qqbot.onebot.client.OneBotApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

/**
 * QQ Bot 业务层入口。
 *
 * <p>它和 NapCat 是两个独立进程，通过网络说话：
 * <pre>
 *   NapCat（协议层）──HTTP 上报──▶ 本工程 ──HTTP 动作──▶ NapCat ──▶ QQ
 *                      /onebot/event        /send_group_msg
 * </pre>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({OneBotProperties.class, GuardProperties.class, LlmProperties.class,
        AsyncProperties.class, MediaProperties.class, KbProperties.class, QaProperties.class,
        AdminProperties.class, LogProperties.class, CommandProperties.class, PlazaProperties.class,
        BotProperties.class, SiteProperties.class, PersistenceProperties.class})
public class QqbotServerApplication {

    private static final Logger log = LoggerFactory.getLogger(QqbotServerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(QqbotServerApplication.class, args);
    }

    /**
     * 启动时确认协议层是通的，并拿到「机器人自己的 QQ 号」。
     * 这个号是判断群里有没有 @ 自己的依据，拿不到就没法正常工作，所以放在启动阶段做。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.kb.build.enabled", havingValue = "false", matchIfMissing = true)
    ApplicationRunner botStartupCheck(OneBotApiClient apiClient,
                                      BotIdentity identity,
                                      OneBotProperties props) {
        return args -> {
            String tokenDesc = StringUtils.hasText(props.getAccessToken())
                    ? "已设置（" + props.getAccessToken().length() + " 位）"
                    : "未设置";
            log.info("正在连接协议层：{}   Token：{}", props.getApiBase(), tokenDesc);
            try {
                JsonNode info = apiClient.getLoginInfo();
                long userId = info.path("user_id").asLong(0);
                String nickname = info.path("nickname").asText("未知");
                identity.setSelfId(userId);
                // 昵称也要存：合并转发的每个节点要写"这条是谁说的"
                identity.setSelfName(nickname);
                log.info("协议层连通 ✓  机器人：{}（QQ {}）", nickname, userId);
            } catch (Exception e) {
                log.error("""
                        
                        连不上 NapCat 的 HTTP 接口，请检查：
                          1) NapCat 容器是否在运行：cd deploy && docker compose ps
                          2) NapCat WebUI 里是否已启用「HTTP服务器」适配器，并设置了 Token
                          3) 下面的配置是否与 NapCat 里的一致：
                             app.onebot.api-base    = {}
                             app.onebot.access-token = {}
                        
                        错误：{}""", props.getApiBase(), tokenDesc, e.getMessage());
            }
        };
    }
}
