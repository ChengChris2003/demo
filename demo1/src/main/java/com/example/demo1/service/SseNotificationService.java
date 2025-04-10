package com.example.demo1.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled; // Import for optional heartbeat
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit; // Import for optional heartbeat

@Service
public class SseNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(SseNotificationService.class);
    // 使用线程安全的列表来存储所有活跃的 SseEmitter 连接
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // 添加一个新的 Emitter
    public void addEmitter(SseEmitter emitter) {
        // 设置完成回调，在连接完成（包括超时、错误后完成）时从列表移除
        emitter.onCompletion(() -> {
            logger.info("SSE Emitter completed. Removing from list. Emitter: {}", emitter);
            this.emitters.remove(emitter);
            logger.info("Emitter removed due to completion. Current count: {}", emitters.size());
        });
        // 设置超时回调
        emitter.onTimeout(() -> {
            logger.info("SSE Emitter timed out. Completing. Emitter: {}", emitter);
            emitter.complete(); // 主动完成，这将触发上面的 onCompletion 回调
            // 无需在此处移除，onCompletion 会处理
        });
        // 设置错误回调
        emitter.onError((e) -> {
            // onError 触发时，连接通常已经或即将关闭，记录警告级别可能更合适
            logger.warn("SSE Emitter error: {}. Emitter: {}", e.getMessage(), emitter);
            // onError 之后通常也会触发 onCompletion，所以在这里移除可能是重复的
            // 但为了确保移除，可以保留，CopyOnWriteArrayList 的 remove 是安全的
            this.emitters.remove(emitter);
            logger.info("Emitter removed due to error. Current count: {}", emitters.size());
        });

        // 将新的 Emitter 添加到列表
        this.emitters.add(emitter);
        logger.info("New SSE Emitter added. Emitter: {}. Current count: {}", emitter, emitters.size());

        // 尝试发送一条初始连接确认事件
        try {
            emitter.send(SseEmitter.event().name("connection").data("SSE Connection established"));
            logger.debug("Sent connection confirmation to new emitter: {}", emitter);
        } catch (IOException e) {
            // 如果初始消息发送失败，可能连接有问题，直接移除
            logger.warn("Error sending initial SSE message, removing emitter: {}, Error: {}", emitter, e.getMessage());
            this.emitters.remove(emitter); // 立即移除失败的 emitter
            logger.info("Emitter removed due to initial send error. Current count: {}", emitters.size());
        } catch (IllegalStateException e) {
            // 处理 emitter 可能已经 complete 的情况
            logger.warn("IllegalStateException sending initial SSE message (emitter likely closed), removing emitter: {}, Error: {}",
                    emitter, e.getMessage());
            this.emitters.remove(emitter);
            logger.info("Emitter removed due to initial send error (IllegalState). Current count: {}", emitters.size());
        }
    }

    // 移除一个 Emitter (主要由回调自动调用，但提供一个公共方法供其他地方按需调用)
    public void removeEmitter(SseEmitter emitter) {
        boolean removed = this.emitters.remove(emitter);
        if (removed) {
            logger.info("SSE Emitter explicitly removed. Emitter: {}. Current count: {}", emitter, emitters.size());
        }
        // 如果 emitter 仍然活跃，可以尝试主动完成它
        // try {
        //     emitter.complete();
        // } catch (Exception e) {
        //     logger.warn("Error completing emitter during manual removal: {}", e.getMessage());
        // }
    }

    /**
     * 向所有当前注册的 SSE emitters 发送事件。
     * 会优雅地处理客户端断开连接导致的 IOException。
     *
     * @param eventName 事件名称
     * @param data      要发送的数据 (通常是 JSON 字符串或可被 MessageConverter 处理的对象)
     */
    public void sendSseEvent(String eventName, Object data) {
        // 如果没有活动的连接，直接返回
        if (emitters.isEmpty()) {
            // logger.debug("No active SSE emitters to send event '{}' to.", eventName); // 可以取消注释以查看空发送日志
            return;
        }

        // 记录将要发送的事件和目标数量
        // 使用 logger.isDebugEnabled() 避免在非 debug 级别下构建复杂的日志字符串
        if (logger.isDebugEnabled()) {
            logger.debug("准备发送 SSE 事件 '{}' 给 {} 个 emitter(s). Data: {}", eventName, emitters.size(), data);
        } else {
            logger.info("准备发送 SSE 事件 '{}' 给 {} 个 emitter(s)", eventName, emitters.size());
        }

        String emitterIdString; // 用于日志记录，避免在移除后无法获取 emitter 信息

        // 遍历 emitters 列表的快照进行发送
        for (SseEmitter emitter : emitters) {
            emitterIdString = emitter.toString(); // 在尝试发送前获取字符串表示
            try {
                // 构建 SSE 事件
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                        .name(eventName) // 事件名称
                        .data(data);    // 事件数据

                // 发送事件
                emitter.send(event);

                // 发送成功日志 (Debug 级别)
                logger.debug("成功发送事件 '{}' 给 emitter: {}", eventName, emitterIdString);

            } catch (IOException e) {
                // **处理 IO 异常**
                String errorMessage = e.getMessage();
                // 检查是否是常见的连接中止错误
                boolean isConnectionAbort = errorMessage != null &&
                        (errorMessage.contains("你的主机中的软件中止了一个已建立的连接") || // 中文 Windows
                                errorMessage.toLowerCase().contains("connection abort") ||       // 常见英文
                                errorMessage.toLowerCase().contains("broken pipe"));           // 常见 Linux/Unix

                if (isConnectionAbort) {
                    // 如果是客户端主动断开连接，记录为 WARN 级别
                    logger.warn("发送 SSE 事件 '{}' 时连接中止 (客户端可能已断开), 移除 emitter: {}, 错误: {}",
                            eventName, emitterIdString, errorMessage);
                } else {
                    // 其他类型的 IO 异常，记录为 ERROR 级别，并包含堆栈跟踪
                    logger.error("发送 SSE 事件 '{}' 时发生 IO 错误, 移除 emitter: {}, 错误: {}",
                            eventName, emitterIdString, errorMessage, e);
                }
                // 无论哪种 IO 异常，都尝试移除这个 emitter
                // 注意：这里调用 removeEmitter 是为了立即尝试移除，但最终移除通常由 onError 或 onCompletion 回调保证
                removeEmitter(emitter); // 立即尝试移除

            } catch (IllegalStateException e) {
                // 处理 emitter 可能已经 complete 或状态非法的情况
                logger.warn("发送 SSE 事件 '{}' 时状态非法 (emitter 可能已关闭), 移除 emitter: {}, 错误: {}",
                        eventName, emitterIdString, e.getMessage());
                removeEmitter(emitter); // 立即尝试移除
            }
            catch (Exception e) {
                // **处理其他未预料的异常**
                logger.error("发送 SSE 事件 '{}' 时发生意外错误, 移除 emitter: {}, 错误: {}",
                        eventName, emitterIdString, e.getMessage(), e); // 包含异常堆栈跟踪
                // 同样移除 emitter
                removeEmitter(emitter); // 立即尝试移除
            }
        }
        // 可选：记录完成发送尝试
        logger.debug("完成对事件 '{}' 的发送尝试。 当前 emitter 数量: {}", eventName, emitters.size());
    }


    // --- 可选: 添加心跳机制 ---
    // 要启用心跳，请取消下面的 @Scheduled 注解,
    // 并在你的主 Spring Boot 应用类 (@SpringBootApplication 注解的类) 上添加 @EnableScheduling 注解。

    /**
     * 定期发送 SSE 心跳（注释形式），以保持连接活跃，防止被中间件（如 Nginx, LB）因空闲超时而断开。
     * 每 25 秒发送一次。
     */
    // @Scheduled(fixedRate = 25, timeUnit = TimeUnit.SECONDS)
    // public void sendSseHeartbeat() {
    //     // 只在有活动连接时发送心跳
    //     if (!emitters.isEmpty()) {
    //         logger.trace("Sending SSE heartbeat to {} emitter(s)...", emitters.size()); // 使用 Trace 级别避免过多日志
    //         // 发送 SSE 注释 (以冒号开头, 后跟可选文本, 以 \n\n 结尾)
    //         // 浏览器 EventSource 会忽略注释行
    //         String heartbeatComment = ": heartbeat\n\n";
    //         // 遍历发送原始文本
    //         for (SseEmitter emitter : emitters) {
    //             String emitterIdString = emitter.toString();
    //             try {
    //                 emitter.send(heartbeatComment);
    //                 // logger.trace("Sent heartbeat comment to emitter: {}", emitterIdString);
    //             } catch (IOException e) {
    //                 // 处理逻辑同 sendSseEvent 中的 IOException
    //                 String errorMessage = e.getMessage();
    //                 boolean isConnectionAbort = errorMessage != null && (errorMessage.contains("你的主机中的软件中止了一个已建立的连接") || errorMessage.toLowerCase().contains("connection abort") || errorMessage.toLowerCase().contains("broken pipe"));
    //                 if (isConnectionAbort) {
    //                     logger.warn("Heartbeat: SSE connection aborted, removing emitter: {}, Error: {}", emitterIdString, errorMessage);
    //                 } else {
    //                     logger.error("Heartbeat: IOException sending SSE comment to emitter: {}, Error: {}", emitterIdString, errorMessage, e);
    //                 }
    //                 removeEmitter(emitter);
    //             } catch (IllegalStateException e) {
    //                  logger.warn("Heartbeat: IllegalStateException sending SSE comment (emitter likely closed), removing emitter: {}, Error: {}", emitterIdString, e.getMessage());
    //                  removeEmitter(emitter);
    //             } catch (Exception e) {
    //                 logger.error("Heartbeat: Unexpected error sending SSE comment to emitter: {}, Error: {}", emitterIdString, e.getMessage(), e);
    //                 removeEmitter(emitter);
    //             }
    //         }
    //     }
    // }
}